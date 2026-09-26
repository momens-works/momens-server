package works.momens.server.mobile.board;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import works.momens.server.auth.AccessTokenTestFactory;
import works.momens.server.common.test.AbstractPostgresIntegrationTest;
import works.momens.server.user.UserProfile;
import works.momens.server.user.UserService;

/**
 * {@code /api/mobile/projects/{projectId}/tasks} 보드 조회와 생성을 실제 Spring 컨텍스트로 확인하는 통합 테스트입니다.
 *
 * <p>실제 토큰(auth 모듈이 제공하는 test fixture)과 실제 PostgreSQL을 사용해 보안 필터부터 권한 검사, 보드 그룹 구성, 라벨 발급, 응답 형태까지
 * 검증합니다. 사용자는 user public API로 생성합니다. workspace와 멤버십, project, task는 아직 생성 public API가
 * 없거나(workspace 쪽) 시드를 간단히 하려고 각 모듈 스키마에 SQL로 직접 넣습니다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MobileProjectTasksIntegrationTest extends AbstractPostgresIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private AccessTokenTestFactory accessTokens;
  @Autowired private UserService userService;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void boardGroupsAllFiveStatusesInOrder() throws Exception {
    UserProfile jinsu = userService.findOrCreate("tasks-it-board@momens.works", "신진수", null);
    UUID workspace = insertWorkspace("tasks-board");
    addMember(workspace, jinsu.id(), "owner");
    UUID project = insertProject(workspace, jinsu.id(), "tasks-board-project");

    insertTask(workspace, project, "백로그", "backlog", "medium", "pm");
    insertTask(workspace, project, "긴급 투두", "todo", "urgent", "frontend");
    insertTask(workspace, project, "진행중", "in_progress", "medium", "pm");
    insertTask(workspace, project, "완료", "done", "low", "pm");
    insertTask(workspace, project, "취소", "cancelled", "high", "pm");

    mockMvc
        .perform(
            get("/api/mobile/projects/{projectId}/tasks", project)
                .header("Authorization", "Bearer " + accessTokens.issueAccessToken(jinsu.id()))
                .header("API-Version", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.title").value("프로젝트 태스크"))
        .andExpect(jsonPath("$.groups.length()").value(5))
        .andExpect(jsonPath("$.groups[0].group_key").value("todo"))
        .andExpect(jsonPath("$.groups[0].count").value(1))
        .andExpect(jsonPath("$.groups[0].tasks[0].title").value("긴급 투두"))
        // 레거시에만 있는 urgent는 모바일 표기에서 high로 내린다.
        .andExpect(jsonPath("$.groups[0].tasks[0].priority").value("high"))
        .andExpect(jsonPath("$.groups[0].tasks[0].role").value("frontend"))
        .andExpect(jsonPath("$.groups[0].tasks[0].material_count").value(0))
        .andExpect(jsonPath("$.groups[1].group_key").value("in_progress"))
        .andExpect(jsonPath("$.groups[1].count").value(1))
        .andExpect(jsonPath("$.groups[2].group_key").value("done"))
        .andExpect(jsonPath("$.groups[2].count").value(1))
        .andExpect(jsonPath("$.groups[3].group_key").value("backlog"))
        .andExpect(jsonPath("$.groups[3].count").value(1))
        .andExpect(jsonPath("$.groups[3].tasks[0].title").value("백로그"))
        .andExpect(jsonPath("$.groups[4].group_key").value("cancelled"))
        .andExpect(jsonPath("$.groups[4].count").value(1));
  }

  @Test
  void createTaskPersistsTodoTaskWithMomLabel() throws Exception {
    UserProfile jinsu = userService.findOrCreate("tasks-it-create@momens.works", "신진수", null);
    UUID workspace = insertWorkspace("tasks-create");
    addMember(workspace, jinsu.id(), "owner");
    UUID project = insertProject(workspace, jinsu.id(), "tasks-create-project");

    mockMvc
        .perform(
            post("/api/mobile/projects/{projectId}/tasks", project)
                .header("Authorization", "Bearer " + accessTokens.issueAccessToken(jinsu.id()))
                .header("API-Version", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"권한 요청 점검\",\"role\":\"backend\",\"priority\":\"high\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.task.project_id").value(project.toString()))
        .andExpect(jsonPath("$.task.title").value("권한 요청 점검"))
        .andExpect(jsonPath("$.task.status").value("todo"))
        .andExpect(jsonPath("$.task.priority").value("high"))
        .andExpect(jsonPath("$.task.role").value("backend"));

    Map<String, Object> row =
        jdbcTemplate.queryForMap(
            "SELECT status, priority, role, label, workspace_id FROM tasks WHERE project_id = ?",
            project);
    org.assertj.core.api.Assertions.assertThat(row.get("status")).isEqualTo("todo");
    org.assertj.core.api.Assertions.assertThat(row.get("priority")).isEqualTo("high");
    org.assertj.core.api.Assertions.assertThat(row.get("role")).isEqualTo("backend");
    org.assertj.core.api.Assertions.assertThat((String) row.get("label")).startsWith("MOM-");
    org.assertj.core.api.Assertions.assertThat(row.get("workspace_id")).isEqualTo(workspace);
  }

  @Test
  void createTaskRejectsBlankTitleWithStandardError() throws Exception {
    UserProfile jinsu = userService.findOrCreate("tasks-it-validation@momens.works", "신진수", null);
    UUID workspace = insertWorkspace("tasks-validation");
    addMember(workspace, jinsu.id(), "owner");
    UUID project = insertProject(workspace, jinsu.id(), "tasks-validation-project");

    mockMvc
        .perform(
            post("/api/mobile/projects/{projectId}/tasks", project)
                .header("Authorization", "Bearer " + accessTokens.issueAccessToken(jinsu.id()))
                .header("API-Version", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"  \",\"role\":\"pm\",\"priority\":\"medium\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("COMMON_VALIDATION_FAILED"));
  }

  @Test
  void returnsForbiddenWhenCallerIsNotWorkspaceMember() throws Exception {
    UserProfile gyuil = userService.findOrCreate("tasks-it-owner@momens.works", "김규일", null);
    UserProfile jinsu = userService.findOrCreate("tasks-it-stranger@momens.works", "신진수", null);
    UUID workspace = insertWorkspace("tasks-forbidden");
    addMember(workspace, gyuil.id(), "owner");
    UUID project = insertProject(workspace, gyuil.id(), "tasks-forbidden-project");

    mockMvc
        .perform(
            get("/api/mobile/projects/{projectId}/tasks", project)
                .header("Authorization", "Bearer " + accessTokens.issueAccessToken(jinsu.id()))
                .header("API-Version", "1"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("AUTH_FORBIDDEN"));
  }

  @Test
  void returnsNotFoundForUnknownProject() throws Exception {
    UserProfile caller = userService.findOrCreate("tasks-it-404@momens.works", "신진수", null);

    mockMvc
        .perform(
            get("/api/mobile/projects/{projectId}/tasks", UUID.randomUUID())
                .header("Authorization", "Bearer " + accessTokens.issueAccessToken(caller.id()))
                .header("API-Version", "1"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("PROJECT_NOT_FOUND"));
  }

  @Test
  void returnsStandardUnauthorizedWithoutToken() throws Exception {
    mockMvc
        .perform(
            get("/api/mobile/projects/{projectId}/tasks", UUID.randomUUID())
                .header("API-Version", "1"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error.code").value("AUTH_UNAUTHORIZED"));
  }

  private UUID insertWorkspace(String slug) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO workspaces (id, name, slug) VALUES (?, ?, ?)", id, "모멘스", slug);
    return id;
  }

  private void addMember(UUID workspaceId, UUID userId, String role) {
    jdbcTemplate.update(
        "INSERT INTO workspace_members (workspace_id, user_id, role) VALUES (?, ?, ?)",
        workspaceId,
        userId,
        role);
  }

  private UUID insertProject(UUID workspaceId, UUID ownerId, String name) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO projects (id, workspace_id, name, owner_id) VALUES (?, ?, ?, ?)",
        id,
        workspaceId,
        name,
        ownerId);
    return id;
  }

  private UUID insertTask(
      UUID workspaceId, UUID projectId, String title, String status, String priority, String role) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO tasks (id, workspace_id, project_id, title, status, priority, role)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?)",
        id,
        workspaceId,
        projectId,
        title,
        status,
        priority,
        role);
    return id;
  }
}
