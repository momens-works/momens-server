package works.momens.server.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import works.momens.server.auth.AccessTokenTestFactory;
import works.momens.server.common.api.BusinessException;
import works.momens.server.common.test.AbstractPostgresIntegrationTest;
import works.momens.server.mcp.grant.CreateMcpGrantCommand;
import works.momens.server.mcp.grant.McpGrantWriter;
import works.momens.server.mcp.transport.McpAuthenticationContext;
import works.momens.server.mcp.transport.McpToolExecutor;
import works.momens.server.project.milestone.MilestoneWriter;
import works.momens.server.project.milestone.UpdateMilestoneCommand;
import works.momens.server.project.task.ApplyTaskDraftCommand;
import works.momens.server.project.task.PatchTaskCommand;
import works.momens.server.project.task.TaskDraftApplier;
import works.momens.server.project.task.TaskDraftValues;
import works.momens.server.project.task.TaskPriority;
import works.momens.server.project.task.TaskRole;
import works.momens.server.project.task.TaskStatus;
import works.momens.server.project.task.TaskWriter;
import works.momens.server.project.task.UpdateTaskCommand;
import works.momens.server.user.UserService;

@SpringBootTest
@AutoConfigureMockMvc
class WriterConcurrencyIntegrationTest extends AbstractPostgresIntegrationTest {

  @Autowired private MockMvc mvc;
  @Autowired private AccessTokenTestFactory accessTokens;
  @Autowired private McpToolExecutor tools;
  @Autowired private McpGrantWriter grants;
  @Autowired private ObjectMapper mapper;
  @Autowired private TaskDraftApplier draftApplier;
  @Autowired private TaskWriter taskWriter;
  @Autowired private MilestoneWriter milestoneWriter;
  @Autowired private UserService userService;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private EntityManager entityManager;

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @DisplayName("서로 다른 필드의 동시 수정도 충돌로 실패하고 먼저 커밋된 값을 유지한다")
  void independentFieldsConflict(boolean milestone) throws Exception {
    UUID id = fixture(milestone);
    assertThat(
            compete(
                () -> update(milestone, id, "변경 이름", null),
                () -> update(milestone, id, null, "변경 설명")))
        .isEqualTo("conflict");
    assertThat(
            jdbc.queryForMap(
                "SELECT "
                    + nameColumn(milestone)
                    + ", description FROM "
                    + table(milestone)
                    + " WHERE id = ?",
                id))
        .containsEntry(nameColumn(milestone), "변경 이름")
        .containsEntry("description", null);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @DisplayName("삭제가 먼저 커밋되면 겹친 수정이 충돌로 실패한다")
  void deletionRejectsWaitingUpdate(boolean milestone) throws Exception {
    UUID id = fixture(milestone);
    String result =
        compete(() -> deleteEntity(milestone, id), () -> update(milestone, id, "복원되면 안 됨", null));
    assertThat(result).isEqualTo("conflict");
    assertThat(
            jdbc.queryForObject(
                "SELECT deleted_at IS NOT NULL FROM " + table(milestone) + " WHERE id = ?",
                Boolean.class,
                id))
        .isTrue();
    if (!milestone) {
      assertThat(
              jdbc.queryForList(
                  "SELECT event_type FROM outbox_events WHERE aggregate_id = ?",
                  String.class,
                  id.toString()))
          .containsExactly("task.deleted");
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void sameFieldConflicts(boolean milestone) throws Exception {
    UUID id = fixture(milestone);
    assertThat(
            compete(
                () -> update(milestone, id, "첫 변경", null),
                () -> update(milestone, id, "두 번째 변경", null)))
        .isEqualTo("conflict");
    assertThat(
            jdbc.queryForObject(
                "SELECT " + nameColumn(milestone) + " FROM " + table(milestone) + " WHERE id = ?",
                String.class,
                id))
        .isEqualTo("첫 변경");
    if (!milestone) {
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM outbox_events WHERE aggregate_id = ?",
                  Integer.class,
                  id.toString()))
          .isEqualTo(1);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void updateRejectsWaitingDelete(boolean milestone) throws Exception {
    UUID id = fixture(milestone);
    assertThat(compete(() -> update(milestone, id, "변경", null), () -> deleteEntity(milestone, id)))
        .isEqualTo("conflict");
    assertThat(
            jdbc.queryForObject(
                "SELECT deleted_at IS NULL FROM " + table(milestone) + " WHERE id = ?",
                Boolean.class,
                id))
        .isTrue();
  }

  @Test
  void checklistChangeConflictsWithFullEditAndRollsBack() throws Exception {
    UUID id = fixture(false);
    UUID itemId = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO task_checklist_items(id,task_id,title,completed,position) VALUES (?,?,'기준',false,0)",
        itemId,
        id);
    assertThat(
            compete(
                () -> taskWriter.toggleChecklistItem(id, itemId, true, null),
                () ->
                    taskWriter.update(
                        new UpdateTaskCommand(
                            id,
                            "덮어쓰기",
                            TaskRole.BACKEND,
                            null,
                            TaskPriority.MEDIUM,
                            TaskStatus.TODO,
                            "설명",
                            List.of(),
                            null))))
        .isEqualTo("conflict");
    assertThat(
            jdbc.queryForObject(
                "SELECT completed FROM task_checklist_items WHERE id=?", Boolean.class, itemId))
        .isTrue();
    assertThat(jdbc.queryForObject("SELECT title FROM tasks WHERE id=?", String.class, id))
        .isEqualTo("원래 이름");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE aggregate_id=?",
                Integer.class,
                id.toString()))
        .isZero();
  }

  @Test
  void draftChangeConflictsWithWaitingPatch() throws Exception {
    UUID id = fixture(false);
    jdbc.update("UPDATE tasks SET role='backend' WHERE id=?", id);
    assertThat(
            compete(
                () ->
                    draftApplier.apply(
                        new ApplyTaskDraftCommand(
                            id,
                            new TaskDraftValues("원래 이름", TaskRole.BACKEND, TaskPriority.MEDIUM),
                            new TaskDraftValues("AI 제목", TaskRole.BACKEND, TaskPriority.HIGH))),
                () -> update(false, id, null, "설명")))
        .isEqualTo("conflict");
    assertThat(jdbc.queryForObject("SELECT title FROM tasks WHERE id=?", String.class, id))
        .isEqualTo("AI 제목");
  }

  @Test
  void webVersionIsOptionalButStaleAndNegativeVersionsAreRejected() throws Exception {
    UUID id = fixture(false);
    String token = tokenFor(id);
    mvc.perform(get("/api/tasks/" + id).header("Authorization", token).header("API-Version", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(0));
    mvc.perform(
            patch("/api/tasks/" + id)
                .header("Authorization", token)
                .header("API-Version", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"첫 수정\",\"version\":0}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(1));
    mvc.perform(
            patch("/api/tasks/" + id)
                .header("Authorization", token)
                .header("API-Version", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"description\":\"오래된 화면\",\"version\":0}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("COMMON_CONCURRENT_MODIFICATION"));
    mvc.perform(
            delete("/api/tasks/" + id)
                .header("Authorization", token)
                .header("API-Version", "1")
                .param("version", "0"))
        .andExpect(status().isConflict());
    mvc.perform(
            patch("/api/tasks/" + id)
                .header("Authorization", token)
                .header("API-Version", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":-1}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.details.fields[0].field").value("version"));
    mvc.perform(
            patch("/api/tasks/" + id)
                .header("Authorization", token)
                .header("API-Version", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"버전 생략\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(2));
    mvc.perform(
            delete("/api/tasks/" + id)
                .header("Authorization", token)
                .header("API-Version", "1")
                .param("version", "2"))
        .andExpect(status().isOk());
  }

  @Test
  void mobileChecklistReturnsVersionAndStaleFullEditFails() throws Exception {
    UUID id = fixture(false);
    UUID itemId = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO task_checklist_items(id,task_id,title,completed,position) VALUES (?,?,'기준',false,0)",
        itemId,
        id);
    String token = tokenFor(id);
    mvc.perform(
            patch("/api/mobile/tasks/" + id + "/checklist-items/" + itemId)
                .header("Authorization", token)
                .header("API-Version", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"completed\":true,\"version\":0}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(1));
    mvc.perform(
            patch("/api/mobile/tasks/" + id)
                .header("Authorization", token)
                .header("API-Version", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"title\":\"수정\",\"role\":\"backend\",\"priority\":\"medium\",\"status\":\"todo\",\"version\":0}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("COMMON_CONCURRENT_MODIFICATION"));
    mvc.perform(
            get("/api/mobile/tasks/" + id)
                .header("Authorization", token)
                .header("API-Version", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(1))
        .andExpect(jsonPath("$.checklist.items[0].completed").value(true));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void mcpReturnsVersionsAndStaleWritesAreToolErrors(boolean milestone) throws Exception {
    UUID id = fixture(milestone);
    UUID projectId =
        jdbc.queryForObject(
            "SELECT project_id FROM " + table(milestone) + " WHERE id=?", UUID.class, id);
    UUID userId =
        jdbc.queryForObject("SELECT owner_id FROM projects WHERE id=?", UUID.class, projectId);
    UUID workspaceId =
        jdbc.queryForObject("SELECT workspace_id FROM projects WHERE id=?", UUID.class, projectId);
    Set<String> scopes =
        Set.of("mcp:tasks:read", "mcp:tasks:write", "mcp:milestones:read", "mcp:milestones:write");
    var grant =
        grants.create(new CreateMcpGrantCommand(userId, "version-client", workspaceId, scopes));
    var context =
        new McpAuthenticationContext(grant.id(), userId, "version-client", workspaceId, scopes);
    var args =
        mapper
            .createObjectNode()
            .put(milestone ? "milestone" : "task", id.toString())
            .put(milestone ? "name" : "title", "새 이름")
            .put("version", 0);
    var success =
        tools.call(milestone ? "update_milestone" : "update_task", args, context).orElseThrow();
    assertThat(success.path("isError").asBoolean()).isFalse();
    assertThat(success.path("content").get(0).path("text").asText()).contains("version: 1");
    var conflict =
        tools.call(milestone ? "update_milestone" : "update_task", args, context).orElseThrow();
    assertThat(conflict.path("isError").asBoolean()).isTrue();
    assertThat(conflict.path("content").get(0).path("text").asText()).contains("Conflict:");
    if (milestone) {
      var result =
          tools
              .call(
                  "delete_milestone",
                  mapper.createObjectNode().put("milestone", id.toString()).put("version", 0),
                  context)
              .orElseThrow();
      assertThat(result.path("isError").asBoolean()).isTrue();
    }
  }

  private String tokenFor(UUID id) {
    UUID userId =
        jdbc.queryForObject(
            "SELECT p.owner_id FROM projects p JOIN tasks t ON t.project_id=p.id WHERE t.id=?",
            UUID.class,
            id);
    return "Bearer " + accessTokens.issueAccessToken(userId);
  }

  private String compete(Runnable first, Runnable second) throws Exception {
    CountDownLatch firstFlushed = new CountDownLatch(1);
    CountDownLatch releaseFirst = new CountDownLatch(1);
    AtomicInteger firstPid = new AtomicInteger();
    try (var executor = Executors.newFixedThreadPool(2)) {
      var firstResult =
          executor.submit(
              () ->
                  new TransactionTemplate(transactionManager)
                      .executeWithoutResult(
                          status -> {
                            firstPid.set(
                                jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                            first.run();
                            entityManager.flush();
                            firstFlushed.countDown();
                            await(releaseFirst);
                          }));
      try {
        assertThat(firstFlushed.await(10, TimeUnit.SECONDS)).isTrue();
        var secondResult =
            executor.submit(
                () -> {
                  try {
                    second.run();
                    return "ok";
                  } catch (OptimisticLockingFailureException exception) {
                    return "conflict";
                  } catch (BusinessException exception) {
                    return exception.getErrorCode().code();
                  }
                });
        try {
          long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
          int blocked;
          do {
            blocked =
                jdbc.queryForObject(
                    "SELECT count(*) FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid))",
                    Integer.class,
                    firstPid.get());
            if (blocked > 0) break;
            Thread.sleep(20);
          } while (System.nanoTime() < deadline);
          assertThat(blocked).as("두 번째 writer가 첫 트랜잭션의 행 잠금을 기다린다").isPositive();
        } finally {
          releaseFirst.countDown();
        }
        firstResult.get(15, TimeUnit.SECONDS);
        return secondResult.get(15, TimeUnit.SECONDS);
      } finally {
        releaseFirst.countDown();
      }
    }
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(15, TimeUnit.SECONDS)) throw new AssertionError("커밋 대기 시간 초과");
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new AssertionError(exception);
    }
  }

  private void update(boolean milestone, UUID id, String name, String description) {
    if (milestone) {
      milestoneWriter.update(
          new UpdateMilestoneCommand(id, name, description, null, null, null, null, null, null));
    } else {
      taskWriter.patch(
          new PatchTaskCommand(
              id,
              name,
              name != null,
              description,
              description != null,
              null,
              false,
              null,
              false,
              null,
              false,
              null,
              false,
              null,
              false,
              null));
    }
  }

  private void deleteEntity(boolean milestone, UUID id) {
    if (milestone) milestoneWriter.delete(id, null);
    else taskWriter.delete(id, null);
  }

  private UUID fixture(boolean milestone) {
    UUID userId =
        userService
            .findOrCreate("writer-race-" + UUID.randomUUID() + "@momens.works", "작성자", null)
            .id();
    UUID workspaceId = UUID.randomUUID();
    UUID projectId = UUID.randomUUID();
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO workspaces (id, name, slug) VALUES (?, '모멘스', ?)",
        workspaceId,
        "race-" + workspaceId);
    jdbc.update(
        "INSERT INTO workspace_members(workspace_id,user_id,role) VALUES (?, ?, 'owner')",
        workspaceId,
        userId);
    jdbc.update(
        "INSERT INTO projects (id, workspace_id, name, owner_id) VALUES (?, ?, '프로젝트', ?)",
        projectId,
        workspaceId,
        userId);
    if (milestone) {
      jdbc.update(
          "INSERT INTO milestones (id, project_id, name) VALUES (?, ?, '원래 이름')", id, projectId);
    } else {
      jdbc.update(
          "INSERT INTO tasks (id, workspace_id, project_id, title, status, priority) VALUES (?, ?, ?, '원래 이름', 'todo', 'medium')",
          id,
          workspaceId,
          projectId);
    }
    return id;
  }

  private static String table(boolean milestone) {
    return milestone ? "milestones" : "tasks";
  }

  private static String nameColumn(boolean milestone) {
    return milestone ? "name" : "title";
  }
}
