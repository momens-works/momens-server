package works.momens.server.web.task;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import works.momens.server.auth.AccessTokenTestFactory;
import works.momens.server.common.test.AbstractPostgresIntegrationTest;
import works.momens.server.user.UserProfile;
import works.momens.server.user.UserService;

@SpringBootTest
@AutoConfigureMockMvc
class WebTaskReadIntegrationTest extends AbstractPostgresIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private AccessTokenTestFactory accessTokens;
  @Autowired private UserService userService;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  @DisplayName("웹 task write는 레거시 PATCH no-op과 update kind 정규화를 보존한다")
  void preservesLegacyWriteSemantics() throws Exception {
    UserProfile caller = userService.findOrCreate("web-task-write@momens.works", "홍길동", null);
    UUID workspaceId = insertWorkspace();
    UUID projectId = insertProject(workspaceId, caller.id());
    UUID taskId = insertTask(workspaceId, projectId);
    addMember(workspaceId, caller.id());

    mockMvc
        .perform(
            authorized(patch("/api/tasks/{taskId}", taskId), caller.id())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"\",\"status\":\"\",\"priority\":\"\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.title").value("태스크"))
        .andExpect(jsonPath("$.status").value("todo"))
        .andExpect(jsonPath("$.priority").value("medium"));

    mockMvc
        .perform(
            authorized(post("/api/tasks/{taskId}/updates", taskId), caller.id())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\" 내용 \",\"kind\":\" Comment \"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.body").value("내용"))
        .andExpect(jsonPath("$.kind").value("comment"));
  }

  @Test
  @DisplayName("워크스페이스 멤버는 태스크·목록·업데이트·컨텍스트를 조회한다")
  void servesTaskUpdatesAndContextForWorkspaceMember() throws Exception {
    UserProfile caller = userService.findOrCreate("web-task-read@momens.works", "홍길동", null);
    UUID workspaceId = insertWorkspace();
    UUID projectId = insertProject(workspaceId, caller.id());
    UUID taskId = insertTask(workspaceId, projectId);
    UUID memoryId = UUID.randomUUID();
    UUID sourceRefId = UUID.randomUUID();
    addMember(workspaceId, caller.id());
    jdbcTemplate.update(
        "INSERT INTO task_updates (id, workspace_id, project_id, task_id, body, kind) VALUES (?, ?, ?, ?, ?, 'comment')",
        UUID.randomUUID(),
        workspaceId,
        projectId,
        taskId,
        "첫 업데이트");
    jdbcTemplate.update(
        "INSERT INTO confirmed_memories (id, workspace_id, memory_type, title) VALUES (?, ?, 'DECISION', '결정')",
        memoryId,
        workspaceId);
    jdbcTemplate.update(
        "INSERT INTO source_refs (id, workspace_id, source_type, source_object_type, source_object_id, title) VALUES (?, ?, 'NOTION', 'PAGE', 'page-1', '근거')",
        sourceRefId,
        workspaceId);
    link(workspaceId, taskId, "MEMORY", memoryId);
    link(workspaceId, taskId, "SOURCE_OBJECT", sourceRefId);

    mockMvc
        .perform(authorized(get("/api/tasks/{taskId}", taskId), caller.id()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(taskId.toString()))
        .andExpect(jsonPath("$.label").value("MOM-100"));
    mockMvc
        .perform(authorized(get("/api/projects/{projectId}/tasks", projectId), caller.id()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tasks[0].id").value(taskId.toString()));
    mockMvc
        .perform(authorized(get("/api/tasks/{taskId}/updates", taskId), caller.id()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.updates[0].body").value("첫 업데이트"));
    mockMvc
        .perform(authorized(get("/api/tasks/{taskId}/context", taskId), caller.id()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.task_id").value(taskId.toString()))
        .andExpect(jsonPath("$.memories[0].id").value(memoryId.toString()))
        .andExpect(jsonPath("$.source_refs[0].id").value(sourceRefId.toString()));
  }

  @Test
  @DisplayName("없는 태스크는 멤버십 확인보다 먼저 TASK_NOT_FOUND를 응답한다")
  void returnsTaskNotFoundBeforeMembershipCheck() throws Exception {
    UserProfile caller = userService.findOrCreate("web-task-404@momens.works", "홍길동", null);

    mockMvc
        .perform(authorized(get("/api/tasks/{taskId}", UUID.randomUUID()), caller.id()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("TASK_NOT_FOUND"));
  }

  @Test
  @DisplayName("프로젝트가 소프트 삭제되면 태스크 상세·업데이트·컨텍스트는 TASK_NOT_FOUND를 응답한다")
  void hidesTasksWhoseProjectIsSoftDeleted() throws Exception {
    UserProfile caller =
        userService.findOrCreate("web-task-project-deleted@momens.works", "홍길동", null);
    UUID workspaceId = insertWorkspace();
    UUID projectId = insertProject(workspaceId, caller.id());
    UUID taskId = insertTask(workspaceId, projectId);
    addMember(workspaceId, caller.id());
    jdbcTemplate.update(
        "UPDATE projects SET deleted_at = ? WHERE id = ?",
        Timestamp.from(Instant.now()),
        projectId);

    for (String path :
        new String[] {
          "/api/tasks/{taskId}", "/api/tasks/{taskId}/updates", "/api/tasks/{taskId}/context"
        }) {
      mockMvc
          .perform(authorized(get(path, taskId), caller.id()))
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.error.code").value("TASK_NOT_FOUND"));
    }
  }

  @Test
  @DisplayName("워크스페이스 멤버가 아니면 태스크 조회는 AUTH_FORBIDDEN을 응답한다")
  void rejectsTaskReadForNonMember() throws Exception {
    UserProfile caller = userService.findOrCreate("web-task-forbidden@momens.works", "홍길동", null);
    UUID workspaceId = insertWorkspace();
    UUID projectId = insertProject(workspaceId, caller.id());
    UUID taskId = insertTask(workspaceId, projectId);

    mockMvc
        .perform(authorized(get("/api/tasks/{taskId}", taskId), caller.id()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("AUTH_FORBIDDEN"))
        .andExpect(jsonPath("$.error.details.workspace_id").value(workspaceId.toString()))
        .andExpect(jsonPath("$.error.details.required_role").value("member"));
  }

  @Test
  @DisplayName("task workspace가 달라도 웹 task 조회의 목록·인가 기준은 소속 project workspace를 사용한다")
  void usesProjectWorkspaceForWebTaskReads() throws Exception {
    UserProfile workspaceAMember =
        userService.findOrCreate("web-task-workspace-a@momens.works", "A 멤버", null);
    UserProfile workspaceBMember =
        userService.findOrCreate("web-task-workspace-b@momens.works", "B 멤버", null);
    UUID workspaceAId = insertWorkspace();
    UUID workspaceBId = insertWorkspace();
    UUID projectId = insertProject(workspaceBId, workspaceBMember.id());
    UUID taskId = insertTask(workspaceAId, projectId, "MOM-777");
    addMember(workspaceAId, workspaceAMember.id());
    addMember(workspaceBId, workspaceBMember.id());

    for (String path :
        new String[] {
          "/api/tasks/{taskId}", "/api/tasks/{taskId}/updates", "/api/tasks/{taskId}/context"
        }) {
      mockMvc
          .perform(authorized(get(path, taskId), workspaceAMember.id()))
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.error.code").value("AUTH_FORBIDDEN"));
    }
    mockMvc
        .perform(authorized(get("/api/tasks/{taskId}", taskId), workspaceBMember.id()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(taskId.toString()));
    mockMvc
        .perform(
            authorized(get("/api/projects/{projectId}/tasks", projectId), workspaceBMember.id()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tasks[0].id").value(taskId.toString()));
  }

  @Test
  @DisplayName("태스크 목록은 소프트 삭제를 제외하고 생성 시각 내림차순으로 응답한다")
  void listsLiveTasksInCreatedAtDescendingOrder() throws Exception {
    UserProfile caller = userService.findOrCreate("web-task-list-order@momens.works", "홍길동", null);
    UUID workspaceId = insertWorkspace();
    UUID projectId = insertProject(workspaceId, caller.id());
    UUID olderTaskId = insertTask(workspaceId, projectId, "MOM-101");
    UUID newerTaskId = insertTask(workspaceId, projectId, "MOM-102");
    UUID deletedTaskId = insertTask(workspaceId, projectId, "MOM-103");
    addMember(workspaceId, caller.id());
    jdbcTemplate.update(
        "UPDATE tasks SET created_at = ? WHERE id = ?",
        Timestamp.from(Instant.parse("2026-08-20T00:00:00Z")),
        olderTaskId);
    jdbcTemplate.update(
        "UPDATE tasks SET created_at = ? WHERE id = ?",
        Timestamp.from(Instant.parse("2026-08-21T00:00:00Z")),
        newerTaskId);
    jdbcTemplate.update(
        "UPDATE tasks SET deleted_at = ? WHERE id = ?",
        Timestamp.from(Instant.now()),
        deletedTaskId);

    mockMvc
        .perform(authorized(get("/api/projects/{projectId}/tasks", projectId), caller.id()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tasks.length()").value(2))
        .andExpect(jsonPath("$.tasks[0].id").value(newerTaskId.toString()))
        .andExpect(jsonPath("$.tasks[1].id").value(olderTaskId.toString()));
  }

  @Test
  @DisplayName("태스크 업데이트는 소프트 삭제를 제외하고 생성 시각 오름차순으로 응답하며 빈 metadata를 생략한다")
  void listsLiveUpdatesInCreatedAtAscendingOrder() throws Exception {
    UserProfile caller =
        userService.findOrCreate("web-task-update-order@momens.works", "홍길동", null);
    UUID workspaceId = insertWorkspace();
    UUID projectId = insertProject(workspaceId, caller.id());
    UUID taskId = insertTask(workspaceId, projectId);
    addMember(workspaceId, caller.id());
    UUID olderUpdateId =
        insertUpdate(workspaceId, projectId, taskId, "먼저", Instant.parse("2026-08-20T00:00:00Z"));
    UUID newerUpdateId =
        insertUpdate(workspaceId, projectId, taskId, "나중", Instant.parse("2026-08-21T00:00:00Z"));
    UUID deletedUpdateId =
        insertUpdate(workspaceId, projectId, taskId, "삭제됨", Instant.parse("2026-08-22T00:00:00Z"));
    jdbcTemplate.update(
        "UPDATE task_updates SET deleted_at = ? WHERE id = ?",
        Timestamp.from(Instant.now()),
        deletedUpdateId);

    mockMvc
        .perform(authorized(get("/api/tasks/{taskId}/updates", taskId), caller.id()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.updates.length()").value(2))
        .andExpect(jsonPath("$.updates[0].id").value(olderUpdateId.toString()))
        .andExpect(jsonPath("$.updates[1].id").value(newerUpdateId.toString()))
        .andExpect(jsonPath("$.updates[0].metadata").doesNotExist());
  }

  @Test
  @DisplayName("링크가 없는 태스크의 context는 null 대신 빈 배열을 응답한다")
  void returnsEmptyArraysForTaskWithoutContext() throws Exception {
    UserProfile caller =
        userService.findOrCreate("web-task-empty-context@momens.works", "홍길동", null);
    UUID workspaceId = insertWorkspace();
    UUID projectId = insertProject(workspaceId, caller.id());
    UUID taskId = insertTask(workspaceId, projectId);
    addMember(workspaceId, caller.id());

    mockMvc
        .perform(authorized(get("/api/tasks/{taskId}/context", taskId), caller.id()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.memories").isArray())
        .andExpect(jsonPath("$.memories").isEmpty())
        .andExpect(jsonPath("$.source_refs").isArray())
        .andExpect(jsonPath("$.source_refs").isEmpty());
  }

  @Test
  @DisplayName("태스크 context는 다른 워크스페이스와 소프트 삭제 항목을 제외하고 생성 시각 내림차순으로 응답한다")
  void filtersAndOrdersTaskContext() throws Exception {
    UserProfile caller =
        userService.findOrCreate("web-task-context-order@momens.works", "홍길동", null);
    UUID workspaceId = insertWorkspace();
    UUID projectId = insertProject(workspaceId, caller.id());
    UUID taskId = insertTask(workspaceId, projectId);
    UUID otherWorkspaceId = insertWorkspace();
    addMember(workspaceId, caller.id());
    UUID olderMemoryId = insertMemory(workspaceId, "먼저", Instant.parse("2026-08-20T00:00:00Z"));
    UUID newerMemoryId = insertMemory(workspaceId, "나중", Instant.parse("2026-08-21T00:00:00Z"));
    UUID deletedMemoryId = insertMemory(workspaceId, "삭제됨", Instant.parse("2026-08-22T00:00:00Z"));
    UUID otherMemoryId =
        insertMemory(otherWorkspaceId, "다른 워크스페이스", Instant.parse("2026-08-23T00:00:00Z"));
    UUID olderSourceRefId =
        insertSourceRef(workspaceId, "먼저", Instant.parse("2026-08-20T00:00:00Z"));
    UUID newerSourceRefId =
        insertSourceRef(workspaceId, "나중", Instant.parse("2026-08-21T00:00:00Z"));
    UUID deletedSourceRefId =
        insertSourceRef(workspaceId, "삭제됨", Instant.parse("2026-08-22T00:00:00Z"));
    UUID otherSourceRefId =
        insertSourceRef(otherWorkspaceId, "다른 워크스페이스", Instant.parse("2026-08-23T00:00:00Z"));
    jdbcTemplate.update(
        "UPDATE confirmed_memories SET deleted_at = ? WHERE id = ?",
        Timestamp.from(Instant.now()),
        deletedMemoryId);
    jdbcTemplate.update(
        "UPDATE source_refs SET deleted_at = ? WHERE id = ?",
        Timestamp.from(Instant.now()),
        deletedSourceRefId);
    for (UUID memoryId :
        new UUID[] {olderMemoryId, newerMemoryId, deletedMemoryId, otherMemoryId}) {
      link(workspaceId, taskId, "MEMORY", memoryId);
    }
    for (UUID sourceRefId :
        new UUID[] {olderSourceRefId, newerSourceRefId, deletedSourceRefId, otherSourceRefId}) {
      link(workspaceId, taskId, "SOURCE_OBJECT", sourceRefId);
    }

    mockMvc
        .perform(authorized(get("/api/tasks/{taskId}/context", taskId), caller.id()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.memories.length()").value(2))
        .andExpect(jsonPath("$.memories[0].id").value(newerMemoryId.toString()))
        .andExpect(jsonPath("$.memories[1].id").value(olderMemoryId.toString()))
        .andExpect(jsonPath("$.source_refs.length()").value(2))
        .andExpect(jsonPath("$.source_refs[0].id").value(newerSourceRefId.toString()))
        .andExpect(jsonPath("$.source_refs[1].id").value(olderSourceRefId.toString()));
  }

  private MockHttpServletRequestBuilder authorized(
      MockHttpServletRequestBuilder builder, UUID userId) {
    return builder
        .header("Authorization", "Bearer " + accessTokens.issueAccessToken(userId))
        .header("API-Version", "1");
  }

  private UUID insertWorkspace() {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO workspaces (id, name, slug) VALUES (?, '모멘스', ?)", id, "web-task-" + id);
    return id;
  }

  private UUID insertProject(UUID workspaceId, UUID ownerId) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO projects (id, workspace_id, name, owner_id) VALUES (?, ?, '프로젝트', ?)",
        id,
        workspaceId,
        ownerId);
    return id;
  }

  private UUID insertTask(UUID workspaceId, UUID projectId) {
    return insertTask(workspaceId, projectId, "MOM-100");
  }

  private UUID insertTask(UUID workspaceId, UUID projectId, String label) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO tasks (id, workspace_id, project_id, label, title, status, priority, origin_type) VALUES (?, ?, ?, ?, '태스크', 'todo', 'medium', 'manual')",
        id,
        workspaceId,
        projectId,
        label);
    return id;
  }

  private UUID insertUpdate(
      UUID workspaceId, UUID projectId, UUID taskId, String body, Instant createdAt) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO task_updates (id, workspace_id, project_id, task_id, body, kind, metadata, created_at, updated_at) VALUES (?, ?, ?, ?, ?, 'comment', '{}'::jsonb, ?, ?)",
        id,
        workspaceId,
        projectId,
        taskId,
        body,
        Timestamp.from(createdAt),
        Timestamp.from(createdAt));
    return id;
  }

  private UUID insertMemory(UUID workspaceId, String title, Instant createdAt) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO confirmed_memories (id, workspace_id, memory_type, title, created_at, updated_at) VALUES (?, ?, 'DECISION', ?, ?, ?)",
        id,
        workspaceId,
        title,
        Timestamp.from(createdAt),
        Timestamp.from(createdAt));
    return id;
  }

  private UUID insertSourceRef(UUID workspaceId, String title, Instant createdAt) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO source_refs (id, workspace_id, source_type, source_object_type, source_object_id, title, created_at, updated_at) VALUES (?, ?, 'NOTION', 'PAGE', ?, ?, ?, ?)",
        id,
        workspaceId,
        "page-" + id,
        title,
        Timestamp.from(createdAt),
        Timestamp.from(createdAt));
    return id;
  }

  private void addMember(UUID workspaceId, UUID userId) {
    jdbcTemplate.update(
        "INSERT INTO workspace_members (workspace_id, user_id, role) VALUES (?, ?, 'member')",
        workspaceId,
        userId);
  }

  private void link(UUID workspaceId, UUID taskId, String toType, UUID toId) {
    jdbcTemplate.update(
        "INSERT INTO entity_relations (id, workspace_id, from_entity_type, from_entity_id, relation_type, to_entity_type, to_entity_id) VALUES (?, ?, 'TASK', ?, 'LINKED_TO', ?, ?)",
        UUID.randomUUID(),
        workspaceId,
        taskId,
        toType,
        toId);
  }
}
