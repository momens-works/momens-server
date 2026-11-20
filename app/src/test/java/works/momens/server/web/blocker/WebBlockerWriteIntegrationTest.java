package works.momens.server.web.blocker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import works.momens.server.auth.AccessTokenTestFactory;
import works.momens.server.common.api.BusinessException;
import works.momens.server.common.test.AbstractPostgresIntegrationTest;
import works.momens.server.project.blocker.BlockerDetail;
import works.momens.server.project.blocker.BlockerReader;
import works.momens.server.project.blocker.BlockerWriter;
import works.momens.server.user.UserService;

@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("웹 블로커 쓰기 통합 테스트")
class WebBlockerWriteIntegrationTest extends AbstractPostgresIntegrationTest {
  @Autowired private MockMvc mockMvc;
  @Autowired private AccessTokenTestFactory accessTokens;
  @Autowired private UserService userService;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private DataSource dataSource;
  @Autowired private BlockerWriter writer;
  @Autowired private BlockerReader reader;

  @Test
  @DisplayName("생성·반복 해결·물리 삭제가 snapshot과 outbox에 반영된다")
  void lifecyclePreservesContractAndSnapshot() throws Exception {
    Fixture f = fixture("member");
    mockMvc
        .perform(
            auth(post("/api/tasks/{id}/blockers", f.taskId()), f.userId())
                .contentType("application/json")
                .content("{\"description\":\"배포 승인\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.workspace_id").value(f.workspaceId().toString()))
        .andExpect(jsonPath("$.blocked_entity_id").value(f.taskId().toString()))
        .andExpect(jsonPath("$.blocked_entity_type").value("task"))
        .andExpect(jsonPath("$.status").value("active"))
        .andExpect(jsonPath("$.description").value("배포 승인"))
        .andExpect(jsonPath("$.created_at").isString())
        .andExpect(jsonPath("$.updated_at").isString())
        .andExpect(jsonPath("$.resolved_at").doesNotExist());
    BlockerDetail blocker = reader.listDetailsByWorkspaceId(f.workspaceId()).getFirst();
    assertThat(row(blocker.id()).get("task_id")).isEqualTo(f.taskId());
    assertThat(row(blocker.id()).get("milestone_id")).isNull();
    snapshot(f)
        .andExpect(jsonPath("$.blockers[0].id").value(blocker.id().toString()))
        .andExpect(jsonPath("$.blockers[0].status").value("active"));

    resolve(f, blocker.id())
        .andExpect(status().isOk())
        .andExpect(content().json("{\"message\":\"resolved\"}"));
    Timestamp firstResolved = (Timestamp) row(blocker.id()).get("resolved_at");
    resolve(f, blocker.id()).andExpect(status().isOk());
    Map<String, Object> resolved = row(blocker.id());
    assertThat((Timestamp) resolved.get("resolved_at")).isAfter(firstResolved);
    assertThat(resolved.get("status")).isEqualTo("resolved");
    assertThat(resolved.get("updated_at")).isEqualTo(resolved.get("resolved_at"));
    assertThat(resolved.get("created_at")).isEqualTo(Timestamp.from(blocker.createdAt()));
    snapshot(f)
        .andExpect(jsonPath("$.blockers[0].status").value("resolved"))
        .andExpect(jsonPath("$.blockers[0].resolved_at").isString());
    jdbc.update(
        "UPDATE workspace_members SET role = 'admin' WHERE workspace_id = ? AND user_id = ?",
        f.workspaceId(),
        f.userId());
    mockMvc
        .perform(auth(delete("/api/blockers/{id}", blocker.id()), f.userId()))
        .andExpect(status().isOk())
        .andExpect(content().json("{\"message\":\"deleted\"}"));
    assertThat(reader.workspaceIdOf(blocker.id())).isEmpty();
    snapshot(f).andExpect(jsonPath("$.blockers").isEmpty());
    List<Map<String, Object>> events = events(f);
    assertThat(events)
        .extracting(e -> e.get("event_type"))
        .containsExactly(
            "blocker.created", "blocker.resolved", "blocker.resolved", "blocker.deleted");
    assertThat(events).extracting(e -> e.get("idempotency_key")).doesNotHaveDuplicates();
    assertThat(events.getFirst().get("idempotency_key"))
        .isEqualTo("blocker.created:" + blocker.id());
    assertThat(events.getLast().get("idempotency_key"))
        .isEqualTo("blocker.deleted:" + blocker.id());
    assertThat(events.subList(1, 3))
        .allSatisfy(e ->
            assertThat(e.get("idempotency_key").toString())
                .startsWith("blocker.resolved:" + blocker.id() + ":"));
    assertThat(events)
        .allSatisfy(e -> {
          assertThat(e.get("issued_by")).isEqualTo("api-server");
          assertThat(e.get("aggregate_type")).isEqualTo("blocker");
          assertThat(e.get("aggregate_id")).isEqualTo(blocker.id().toString());
          assertThat(e.get("payload").toString()).isEqualTo("{}");
        });
    mockMvc
        .perform(auth(delete("/api/blockers/{id}", blocker.id()), f.userId()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("BLOCKER_NOT_FOUND"));
    assertThat(events(f)).hasSize(4);
  }

  @ParameterizedTest
  @ValueSource(strings = {"member", "admin", "owner"})
  @DisplayName("모든 멤버 역할이 명시적 workspace와 공백 설명으로 생성하고 해결할 수 있다")
  void membersCanCreateAndResolve(String role) throws Exception {
    Fixture f = fixture(role);
    mockMvc
        .perform(
            auth(post("/api/tasks/{id}/blockers", f.taskId()), f.userId())
                .contentType("application/json")
                .content("{\"workspace_id\":\"" + f.workspaceId() + "\",\"description\":\"   \"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.description").value("   "));
    UUID id = reader.listDetailsByWorkspaceId(f.workspaceId()).getFirst().id();
    resolve(f, id).andExpect(status().isOk());
  }

  @ParameterizedTest
  @ValueSource(strings = {"admin", "owner"})
  @DisplayName("관리자와 소유자가 active blocker를 삭제할 수 있다")
  void privilegedRolesCanDelete(String role) throws Exception {
    Fixture f = fixture(role);
    UUID id = writer.createForTask(f.workspaceId(), f.taskId(), "차단").id();
    mockMvc.perform(auth(delete("/api/blockers/{id}", id), f.userId())).andExpect(status().isOk());
    assertThat(reader.workspaceIdOf(id)).isEmpty();
  }

  @Test
  @DisplayName("비멤버의 생성·해결·삭제와 일반 멤버의 삭제는 DB와 outbox를 바꾸지 않는다")
  void authorizationFailuresDoNotWrite() throws Exception {
    Fixture f = fixture("member");
    Fixture other = fixture("owner");
    UUID id = writer.createForTask(f.workspaceId(), f.taskId(), "차단").id();
    Map<String, Object> before = row(id);
    mockMvc
        .perform(
            auth(post("/api/tasks/{id}/blockers", f.taskId()), other.userId())
                .contentType("application/json")
                .content("{\"description\":\"차단\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.details.required_role").value("member"));
    mockMvc
        .perform(auth(patch("/api/blockers/{id}/resolve", id), other.userId()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("AUTH_FORBIDDEN"));
    for (UUID caller : List.of(f.userId(), other.userId())) {
      mockMvc
          .perform(auth(delete("/api/blockers/{id}", id), caller))
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.error.details.workspace_id").value(f.workspaceId().toString()))
          .andExpect(jsonPath("$.error.details.required_role").value("admin"));
    }
    assertThat(row(id)).isEqualTo(before);
    assertThat(reader.listDetailsByWorkspaceId(f.workspaceId())).hasSize(1);
    assertThat(events(f)).hasSize(1);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{}",
        "{\"description\":null}",
        "{\"description\":\"\"}",
        "{\"description\":\"x\",\"workspace_id\":\"bad\"}"
      })
  @DisplayName("누락·빈 설명과 잘못된 workspace 형식은 생성하지 않는다")
  void invalidRequestsDoNotWrite(String body) throws Exception {
    Fixture f = fixture("member");
    mockMvc
        .perform(
            auth(post("/api/tasks/{id}/blockers", f.taskId()), f.userId())
                .contentType("application/json")
                .content(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").isString());
    assertThat(reader.listDetailsByWorkspaceId(f.workspaceId())).isEmpty();
    assertThat(events(f)).isEmpty();
  }

  @Test
  @DisplayName("workspace 불일치와 삭제된 태스크·프로젝트는 생성하지 않는다")
  void rejectsMismatchedWorkspaceAndDeletedTargets() throws Exception {
    Fixture f = fixture("member");
    mockMvc
        .perform(
            auth(post("/api/tasks/{id}/blockers", f.taskId()), f.userId())
                .contentType("application/json")
                .content("{\"workspace_id\":\"" + UUID.randomUUID() + "\",\"description\":\"차단\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("BLOCKER_WORKSPACE_MISMATCH"));
    jdbc.update("UPDATE tasks SET deleted_at = NOW() WHERE id = ?", f.taskId());
    create(f)
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("TASK_NOT_FOUND"));
    jdbc.update("UPDATE tasks SET deleted_at = NULL WHERE id = ?", f.taskId());
    jdbc.update("UPDATE projects SET deleted_at = NOW() WHERE id = ?", f.projectId());
    create(f)
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("TASK_NOT_FOUND"));
    assertThat(reader.listDetailsByWorkspaceId(f.workspaceId())).isEmpty();
    assertThat(events(f)).isEmpty();
  }

  @Test
  @DisplayName("인증 없음·잘못된 UUID·없는 대상은 Standard 오류를 반환한다")
  void authenticationAndMissingResources() throws Exception {
    Fixture f = fixture("member");
    for (MockHttpServletRequestBuilder request :
        List.of(
            post("/api/tasks/{id}/blockers", f.taskId()),
            patch("/api/blockers/{id}/resolve", UUID.randomUUID()),
            delete("/api/blockers/{id}", UUID.randomUUID()))) {
      mockMvc
          .perform(
              request
                  .header("API-Version", "1")
                  .contentType("application/json")
                  .content("{\"description\":\"차단\"}"))
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.error.code").value("AUTH_UNAUTHORIZED"));
    }
    mockMvc
        .perform(
            auth(post("/api/tasks/bad/blockers"), f.userId())
                .contentType("application/json")
                .content("{\"description\":\"차단\"}"))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(
            auth(post("/api/tasks/{id}/blockers", UUID.randomUUID()), f.userId())
                .contentType("application/json")
                .content("{\"description\":\"차단\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("TASK_NOT_FOUND"));
    resolve(f, UUID.randomUUID())
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("BLOCKER_NOT_FOUND"));
    mockMvc
        .perform(auth(delete("/api/blockers/{id}", UUID.randomUUID()), f.userId()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("BLOCKER_NOT_FOUND"));
    assertThat(events(f)).isEmpty();
  }

  @Test
  @DisplayName("writer도 다른 workspace의 해결·삭제를 거부한다")
  void writerChecksWorkspace() {
    Fixture f = fixture("member");
    UUID id = writer.createForTask(f.workspaceId(), f.taskId(), "차단").id();
    Map<String, Object> before = row(id);
    assertThatThrownBy(() -> writer.resolve(UUID.randomUUID(), id))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> writer.delete(UUID.randomUUID(), id))
        .isInstanceOf(BusinessException.class);
    assertThat(row(id)).isEqualTo(before);
    assertThat(events(f)).hasSize(1);
  }

  @Test
  @DisplayName("레거시 milestone blocker도 기존 대상 참조를 보존하며 해결·삭제한다")
  void legacyMilestoneBlockerRemainsWritable() throws Exception {
    Fixture f = fixture("owner");
    UUID milestone = UUID.randomUUID();
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO milestones(id,project_id,name) VALUES (?,?,'기존 마일스톤')",
        milestone,
        f.projectId());
    jdbc.update(
        "INSERT INTO blockers(id,workspace_id,description,blocked_entity_type,blocked_entity_id,milestone_id) VALUES (?,?,'차단','milestone',?,?)",
        id,
        f.workspaceId(),
        milestone,
        milestone);
    resolve(f, id).andExpect(status().isOk());
    assertThat(row(id).get("milestone_id")).isEqualTo(milestone);
    assertThat(row(id).get("task_id")).isNull();
    mockMvc.perform(auth(delete("/api/blockers/{id}", id), f.userId())).andExpect(status().isOk());
    assertThat(reader.workspaceIdOf(id)).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"blocker.created", "blocker.resolved", "blocker.deleted"})
  @DisplayName("outbox INSERT가 실패하면 생성·해결·삭제가 각각 롤백된다")
  void outboxFailureRollsBackDomain(String eventType) throws Exception {
    Fixture f = fixture("owner");
    UUID id =
        eventType.equals("blocker.created")
            ? null
            : writer.createForTask(f.workspaceId(), f.taskId(), "차단").id();
    Map<String, Object> before = id == null ? null : row(id);
    int count = events(f).size();
    jdbc.execute(
        "CREATE OR REPLACE FUNCTION fail_blocker_outbox() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'blocker outbox failure'; END; $$");
    jdbc.execute(
        "CREATE TRIGGER fail_blocker_outbox BEFORE INSERT ON outbox_events FOR EACH ROW WHEN (NEW.event_type = '"
            + eventType
            + "') EXECUTE FUNCTION fail_blocker_outbox()");
    try {
      switch (eventType) {
        case "blocker.created" -> create(f).andExpect(status().isInternalServerError());
        case "blocker.resolved" -> resolve(f, id).andExpect(status().isInternalServerError());
        case "blocker.deleted" ->
            mockMvc
                .perform(auth(delete("/api/blockers/{id}", id), f.userId()))
                .andExpect(status().isInternalServerError());
        default -> throw new AssertionError(eventType);
      }
    } finally {
      jdbc.execute("DROP TRIGGER fail_blocker_outbox ON outbox_events");
      jdbc.execute("DROP FUNCTION fail_blocker_outbox()");
    }
    if (id == null) assertThat(reader.listDetailsByWorkspaceId(f.workspaceId())).isEmpty();
    else assertThat(row(id)).isEqualTo(before);
    assertThat(events(f)).hasSize(count);
  }

  @ParameterizedTest
  @ValueSource(strings = {"resolve", "delete"})
  @DisplayName("동시 해결·삭제와 이중 삭제가 blocker를 복원하거나 삭제 이벤트를 중복 발행하지 않는다")
  void concurrentCommandsSerialize(String competingAction) throws Exception {
    Fixture f = fixture("owner");
    UUID id = writer.createForTask(f.workspaceId(), f.taskId(), "차단").id();
    try (var connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      JdbcTemplate locker = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
      int lockerPid = locker.queryForObject("SELECT pg_backend_pid()", Integer.class);
      locker.queryForList("SELECT id FROM blockers WHERE id = ? FOR UPDATE", id);
      try (var executor = Executors.newFixedThreadPool(2)) {
        var deletion = executor.submit(() -> runCompetingCommand(f, id, "delete"));
        var other = executor.submit(() -> runCompetingCommand(f, id, competingAction));
        try {
          long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
          int blocked;
          do {
            blocked =
                jdbc.queryForObject(
                    """
                    WITH RECURSIVE blocked AS (
                      SELECT pid FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid))
                      UNION
                      SELECT a.pid FROM pg_stat_activity a JOIN blocked b
                        ON b.pid = ANY(pg_blocking_pids(a.pid)))
                    SELECT count(*) FROM blocked b JOIN pg_stat_activity a ON a.pid = b.pid
                    WHERE a.query ~* 'for (no key )?update' AND a.query LIKE '%blockers%'
                    """,
                    Integer.class, lockerPid);
            if (blocked == 2) {
              break;
            }
            Thread.sleep(20);
          } while (System.nanoTime() < deadline);
          assertThat(blocked).as("두 요청 모두 상태 변경 전 blocker 조회 잠금에서 대기한다").isEqualTo(2);
        } finally {
          connection.rollback();
        }
        List<String> results =
            List.of(deletion.get(15, TimeUnit.SECONDS), other.get(15, TimeUnit.SECONDS));
        if (competingAction.equals("delete")) {
          assertThat(results).containsExactlyInAnyOrder("ok", "BLOCKER_NOT_FOUND");
        } else {
          assertThat(results.getFirst()).isEqualTo("ok");
          assertThat(results.getLast()).isIn("ok", "BLOCKER_NOT_FOUND");
          assertThat(events(f)).hasSize(results.getLast().equals("ok") ? 3 : 2);
        }
      }
    }
    assertThat(reader.workspaceIdOf(id)).isEmpty();
    assertThat(events(f).stream().filter(e -> e.get("event_type").equals("blocker.deleted")))
        .hasSize(1);
    assertThat(events(f).getLast().get("event_type")).isEqualTo("blocker.deleted");
  }

  private String runCompetingCommand(Fixture f, UUID id, String action) {
    try {
      if (action.equals("delete")) writer.delete(f.workspaceId(), id);
      else writer.resolve(f.workspaceId(), id);
      return "ok";
    } catch (BusinessException e) {
      return e.getErrorCode().code();
    }
  }

  private ResultActions create(Fixture f) throws Exception {
    return mockMvc.perform(
        auth(post("/api/tasks/{id}/blockers", f.taskId()), f.userId())
            .contentType("application/json")
            .content("{\"description\":\"차단\"}"));
  }

  private ResultActions resolve(Fixture f, UUID id) throws Exception {
    return mockMvc.perform(auth(patch("/api/blockers/{id}/resolve", id), f.userId()));
  }

  private ResultActions snapshot(Fixture f) throws Exception {
    return mockMvc
        .perform(auth(get("/api/workspaces/{id}/snapshot", f.workspaceId()), f.userId()))
        .andExpect(status().isOk());
  }

  private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request, UUID user) {
    return request
        .header("API-Version", "1")
        .header("Authorization", "Bearer " + accessTokens.issueAccessToken(user));
  }

  private Map<String, Object> row(UUID id) {
    return jdbc.queryForMap("SELECT * FROM blockers WHERE id = ?", id);
  }

  private List<Map<String, Object>> events(Fixture f) {
    return jdbc.queryForList(
        "SELECT * FROM outbox_events WHERE workspace_id = ? ORDER BY id", f.workspaceId());
  }

  private Fixture fixture(String role) {
    UUID user =
        userService
            .findOrCreate("blocker-" + UUID.randomUUID() + "@momens.works", "작성자", null)
            .id();
    UUID workspace = UUID.randomUUID();
    UUID project = UUID.randomUUID();
    UUID task = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO workspaces(id,name,slug) VALUES (?,'블로커 테스트',?)",
        workspace,
        workspace.toString());
    jdbc.update(
        "INSERT INTO workspace_members(workspace_id,user_id,role) VALUES (?,?,?)",
        workspace,
        user,
        role);
    jdbc.update(
        "INSERT INTO projects(id,workspace_id,name,owner_id) VALUES (?,?,'프로젝트',?)",
        project,
        workspace,
        user);
    jdbc.update(
        "INSERT INTO tasks(id,workspace_id,project_id,title) VALUES (?,?,?,'태스크')",
        task,
        workspace,
        project);
    return new Fixture(user, workspace, project, task);
  }

  private record Fixture(UUID userId, UUID workspaceId, UUID projectId, UUID taskId) {}
}
