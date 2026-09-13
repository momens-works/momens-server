package works.momens.server.mobile.signal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import works.momens.server.auth.AccessTokenTestFactory;
import works.momens.server.common.test.AbstractPostgresIntegrationTest;
import works.momens.server.user.UserProfile;
import works.momens.server.user.UserService;

/**
 * {@code GET /api/mobile/projects/{projectId}/signals}(목록)와 {@code GET
 * /api/mobile/signals/{signalId}} (상세) 실배선 통합 테스트.
 *
 * <p>실토큰(auth public testFixtures)과 실제 PostgreSQL로 보안 체인부터 권한 검사, 미처리 필터, evidence hydrate, 응답
 * shape까지 끝까지 확인합니다. 사용자는 user public API로 만들고, workspace/멤버십/project/signals/signal_actions/
 * signal_evidence/source_refs는 아직 생성 public API가 없어 소유 스키마에 SQL로 시드합니다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MobileSignalsIntegrationTest extends AbstractPostgresIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private AccessTokenTestFactory accessTokens;
  @Autowired private UserService userService;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  @DisplayName("workspace 멤버에게 미처리 Signal 목록을 반환한다")
  void returnsUnprocessedSignalsForWorkspaceMember() throws Exception {
    UserProfile jinsu = userService.findOrCreate("signals-it-jinsu@momens.works", "신진수", null);
    UUID workspace = insertWorkspace("signals-list");
    addMember(workspace, jinsu.id(), "owner");
    UUID project = insertProject(workspace, jinsu.id(), "signals-list-project");
    UUID unprocessed = insertSignal(workspace, project, "risk", "이탈 가능성 발견", "완료율에 영향", "점검 제안");
    UUID processed = insertSignal(workspace, project, "decision", "이미 처리됨", null, null);
    insertAction(workspace, processed, jinsu.id());

    mockMvc
        .perform(
            get("/api/mobile/projects/{projectId}/signals", project)
                .header("Authorization", "Bearer " + accessTokens.issueAccessToken(jinsu.id()))
                .header("API-Version", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.title").value("오늘 확인해야 할 시그널"))
        .andExpect(jsonPath("$.signals.length()").value(1))
        .andExpect(jsonPath("$.signals[0].id").value(unprocessed.toString()))
        .andExpect(jsonPath("$.signals[0].type").value("risk"))
        .andExpect(jsonPath("$.signals[0].impact").value("완료율에 영향"))
        .andExpect(jsonPath("$.signals[0].minsu_suggestion").value("점검 제안"));
  }

  @Test
  @DisplayName("workspace 멤버가 아니면 AUTH_FORBIDDEN을 반환한다")
  void returnsForbiddenWhenCallerIsNotWorkspaceMember() throws Exception {
    UserProfile gyuil =
        userService.findOrCreate("signals-it-owner-gyuil@momens.works", "김규일", null);
    UserProfile jinsu =
        userService.findOrCreate("signals-it-stranger-jinsu@momens.works", "신진수", null);
    UUID workspace = insertWorkspace("signals-forbidden");
    addMember(workspace, gyuil.id(), "owner");
    UUID project = insertProject(workspace, gyuil.id(), "signals-forbidden-project");

    // 규일만 멤버인 workspace의 project를 진수 토큰으로 조회한다.
    mockMvc
        .perform(
            get("/api/mobile/projects/{projectId}/signals", project)
                .header("Authorization", "Bearer " + accessTokens.issueAccessToken(jinsu.id()))
                .header("API-Version", "1"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("AUTH_FORBIDDEN"));
  }

  @Test
  @DisplayName("없는 project를 조회하면 PROJECT_NOT_FOUND를 반환한다")
  void returnsNotFoundForUnknownProject() throws Exception {
    UserProfile caller = userService.findOrCreate("signals-it-404@momens.works", "신진수", null);

    mockMvc
        .perform(
            get("/api/mobile/projects/{projectId}/signals", UUID.randomUUID())
                .header("Authorization", "Bearer " + accessTokens.issueAccessToken(caller.id()))
                .header("API-Version", "1"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("PROJECT_NOT_FOUND"));
  }

  @Test
  @DisplayName("토큰 없이 조회하면 AUTH_UNAUTHORIZED를 반환한다")
  void returnsStandardUnauthorizedWithoutToken() throws Exception {
    mockMvc
        .perform(
            get("/api/mobile/projects/{projectId}/signals", UUID.randomUUID())
                .header("API-Version", "1"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error.code").value("AUTH_UNAUTHORIZED"));
  }

  @Test
  @DisplayName("멤버에게 근거의 대상·변화·영향이 담긴 Signal 상세를 반환한다")
  void returnsSignalDetailWithEvidenceDetailsForMember() throws Exception {
    UserProfile jinsu = userService.findOrCreate("signals-it-detail@momens.works", "신진수", null);
    UUID workspace = insertWorkspace("signals-detail");
    addMember(workspace, jinsu.id(), "owner");
    UUID project = insertProject(workspace, jinsu.id(), "Q2 Activation Readiness");
    UUID signal = insertSignal(workspace, project, "risk", "이탈 가능성 발견", "완료율에 영향", "점검 제안");
    // source_ref는 source·occurred_at·source_url을, signal_evidence는 대상·변화·영향을 채운다.
    UUID sourceRef = insertSourceRef(workspace, "figma", "권한 화면", null, "본문 요약", "https://f/1");
    insertEvidence(workspace, signal, sourceRef, 0, "권한 요청 화면", "이탈률 증가", "완료율 저하 가능");

    mockMvc
        .perform(
            get("/api/mobile/signals/{signalId}", signal)
                .header("Authorization", "Bearer " + accessTokens.issueAccessToken(jinsu.id()))
                .header("API-Version", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(signal.toString()))
        .andExpect(jsonPath("$.type").value("risk"))
        .andExpect(jsonPath("$.impact").value("완료율에 영향"))
        .andExpect(jsonPath("$.evidence.length()").value(1))
        .andExpect(jsonPath("$.evidence[0].source_ref_id").value(sourceRef.toString()))
        .andExpect(jsonPath("$.evidence[0].source").value("figma"))
        .andExpect(jsonPath("$.evidence[0].details.target").value("권한 요청 화면"))
        .andExpect(jsonPath("$.evidence[0].details.change").value("이탈률 증가"))
        .andExpect(jsonPath("$.evidence[0].details.impact").value("완료율 저하 가능"))
        .andExpect(jsonPath("$.evidence[0].source_url").value("https://f/1"))
        .andExpect(jsonPath("$.minsu_suggestion").value("점검 제안"))
        // ADR-0011로 상세 응답에서 제외된 필드.
        .andExpect(jsonPath("$.project").doesNotExist())
        .andExpect(jsonPath("$.evidence[0].source_title").doesNotExist())
        .andExpect(jsonPath("$.evidence[0].summary").doesNotExist())
        .andExpect(jsonPath("$.minsu").doesNotExist())
        .andExpect(jsonPath("$.primary_action").doesNotExist());
  }

  @Test
  @DisplayName("이미 처리된 Signal의 상세를 조회하면 SIGNAL_NOT_FOUND를 반환한다")
  void returnsNotFoundOnDetailForProcessedSignal() throws Exception {
    UserProfile jinsu =
        userService.findOrCreate("signals-it-detail-processed@momens.works", "신진수", null);
    UUID workspace = insertWorkspace("signals-detail-processed");
    addMember(workspace, jinsu.id(), "owner");
    UUID project = insertProject(workspace, jinsu.id(), "signals-detail-processed-project");
    UUID signal = insertSignal(workspace, project, "risk", "제목", null, null);
    insertAction(workspace, signal, jinsu.id());

    mockMvc
        .perform(
            get("/api/mobile/signals/{signalId}", signal)
                .header("Authorization", "Bearer " + accessTokens.issueAccessToken(jinsu.id()))
                .header("API-Version", "1"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("SIGNAL_NOT_FOUND"));
  }

  @Test
  @DisplayName("상세 조회에서 workspace 멤버가 아니면 AUTH_FORBIDDEN을 반환한다")
  void returnsForbiddenOnDetailWhenNotMember() throws Exception {
    UserProfile gyuil =
        userService.findOrCreate("signals-it-detail-owner@momens.works", "김규일", null);
    UserProfile jinsu =
        userService.findOrCreate("signals-it-detail-stranger@momens.works", "신진수", null);
    UUID workspace = insertWorkspace("signals-detail-forbidden");
    addMember(workspace, gyuil.id(), "owner");
    UUID project = insertProject(workspace, gyuil.id(), "detail-forbidden-project");
    UUID signal = insertSignal(workspace, project, "risk", "제목", null, null);

    mockMvc
        .perform(
            get("/api/mobile/signals/{signalId}", signal)
                .header("Authorization", "Bearer " + accessTokens.issueAccessToken(jinsu.id()))
                .header("API-Version", "1"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("AUTH_FORBIDDEN"));
  }

  @Test
  @DisplayName("없는 Signal을 조회하면 SIGNAL_NOT_FOUND를 반환한다")
  void returnsNotFoundForUnknownSignal() throws Exception {
    UserProfile caller =
        userService.findOrCreate("signals-it-detail-404@momens.works", "신진수", null);

    mockMvc
        .perform(
            get("/api/mobile/signals/{signalId}", UUID.randomUUID())
                .header("Authorization", "Bearer " + accessTokens.issueAccessToken(caller.id()))
                .header("API-Version", "1"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("SIGNAL_NOT_FOUND"));
  }

  @Test
  @DisplayName("convert-to-task는 태스크를 생성하고 재요청은 멱등 replay로 200을 반환한다")
  void convertToTaskCreatesTaskAndReplaysOnRetry() throws Exception {
    UserProfile jinsu = userService.findOrCreate("signals-it-convert@momens.works", "신진수", null);
    UUID workspace = insertWorkspace("signals-convert");
    addMember(workspace, jinsu.id(), "owner");
    UUID project = insertProject(workspace, jinsu.id(), "signals-convert-project");
    UUID signal = insertSignal(workspace, project, "risk", "이탈 가능성 발견", "완료율에 영향", null);
    String token = "Bearer " + accessTokens.issueAccessToken(jinsu.id());

    mockMvc
        .perform(
            post("/api/mobile/signals/{signalId}/actions/convert-to-task", signal)
                .header("Authorization", token)
                .header("API-Version", "1"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.task.title").value("이탈 가능성 발견"))
        .andExpect(jsonPath("$.task.status").value("todo"))
        .andExpect(jsonPath("$.signal.id").value(signal.toString()))
        .andExpect(jsonPath("$.signal.action").value("convert_to_task"));

    Integer taskCount =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM tasks WHERE project_id = ?", Integer.class, project);
    assertThat(taskCount).isEqualTo(1);
    String taskId =
        jdbcTemplate.queryForObject(
            "SELECT id FROM tasks WHERE project_id = ?", String.class, project);
    // Minsu는 기본 비활성이므로 고정 fallback draft(title=15자로 제한한 Signal title, role=pm,
    // priority=medium)를 쓴다.
    assertThat(
            jdbcTemplate.queryForMap(
                "SELECT role, priority FROM tasks WHERE project_id = ?", project))
        .containsEntry("role", "pm")
        .containsEntry("priority", "medium");

    mockMvc
        .perform(
            post("/api/mobile/signals/{signalId}/actions/convert-to-task", signal)
                .header("Authorization", token)
                .header("API-Version", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.task.id").value(taskId));

    Integer taskCountAfterRetry =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM tasks WHERE project_id = ?", Integer.class, project);
    assertThat(taskCountAfterRetry).isEqualTo(1);
  }

  @Test
  @DisplayName("Minsu 비활성 기본 설정에서 15자를 넘는 Signal title은 15자로 잘린 task title이 된다")
  void convertToTaskTruncatesFallbackTitle() throws Exception {
    UserProfile jinsu = userService.findOrCreate("signals-it-truncate@momens.works", "신진수", null);
    UUID workspace = insertWorkspace("signals-truncate");
    addMember(workspace, jinsu.id(), "owner");
    UUID project = insertProject(workspace, jinsu.id(), "signals-truncate-project");
    UUID signal =
        insertSignal(workspace, project, "risk", "Android 13+ 권한 요청 플로우에서 이탈 가능성 발견", null, null);

    mockMvc
        .perform(
            post("/api/mobile/signals/{signalId}/actions/convert-to-task", signal)
                .header("Authorization", "Bearer " + accessTokens.issueAccessToken(jinsu.id()))
                .header("API-Version", "1"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.task.title").value("Android 13+ 권한"));

    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT title FROM tasks WHERE project_id = ?", String.class, project))
        .isEqualTo("Android 13+ 권한");
  }

  @Test
  @DisplayName("dismiss는 처리 기록만 남기고, 재요청은 멱등 replay로 200을 반환하며 목록에서 제외한다")
  void dismissRecordsActionAndExcludesFromList() throws Exception {
    UserProfile jinsu = userService.findOrCreate("signals-it-dismiss@momens.works", "신진수", null);
    UUID workspace = insertWorkspace("signals-dismiss");
    addMember(workspace, jinsu.id(), "owner");
    UUID project = insertProject(workspace, jinsu.id(), "signals-dismiss-project");
    UUID signal = insertSignal(workspace, project, "decision", "제목", null, null);
    String token = "Bearer " + accessTokens.issueAccessToken(jinsu.id());

    mockMvc
        .perform(
            post("/api/mobile/signals/{signalId}/actions/dismiss", signal)
                .header("Authorization", token)
                .header("API-Version", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.signal.id").value(signal.toString()))
        .andExpect(jsonPath("$.signal.action").value("dismiss"));

    mockMvc
        .perform(
            post("/api/mobile/signals/{signalId}/actions/dismiss", signal)
                .header("Authorization", token)
                .header("API-Version", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.signal.action").value("dismiss"));

    Integer actionCount =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM signal_actions WHERE signal_id = ?", Integer.class, signal);
    assertThat(actionCount).isEqualTo(1);

    mockMvc
        .perform(
            get("/api/mobile/projects/{projectId}/signals", project)
                .header("Authorization", token)
                .header("API-Version", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.signals.length()").value(0));
  }

  @Test
  @DisplayName("이미 dismiss된 Signal에 convert-to-task를 요청하면 SIGNAL_INVALID_STATE를 반환한다")
  void convertToTaskAfterDismissReturnsInvalidState() throws Exception {
    UserProfile jinsu = userService.findOrCreate("signals-it-conflict-1@momens.works", "신진수", null);
    UUID workspace = insertWorkspace("signals-conflict-1");
    addMember(workspace, jinsu.id(), "owner");
    UUID project = insertProject(workspace, jinsu.id(), "signals-conflict-1-project");
    UUID signal = insertSignal(workspace, project, "risk", "제목", null, null);
    String token = "Bearer " + accessTokens.issueAccessToken(jinsu.id());

    mockMvc
        .perform(
            post("/api/mobile/signals/{signalId}/actions/dismiss", signal)
                .header("Authorization", token)
                .header("API-Version", "1"))
        .andExpect(status().isOk());

    mockMvc
        .perform(
            post("/api/mobile/signals/{signalId}/actions/convert-to-task", signal)
                .header("Authorization", token)
                .header("API-Version", "1"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("SIGNAL_INVALID_STATE"));
  }

  @Test
  @DisplayName("이미 전환된 Signal에 dismiss를 요청하면 SIGNAL_INVALID_STATE를 반환한다")
  void dismissAfterConvertToTaskReturnsInvalidState() throws Exception {
    UserProfile jinsu = userService.findOrCreate("signals-it-conflict-2@momens.works", "신진수", null);
    UUID workspace = insertWorkspace("signals-conflict-2");
    addMember(workspace, jinsu.id(), "owner");
    UUID project = insertProject(workspace, jinsu.id(), "signals-conflict-2-project");
    UUID signal = insertSignal(workspace, project, "risk", "제목", null, null);
    String token = "Bearer " + accessTokens.issueAccessToken(jinsu.id());

    mockMvc
        .perform(
            post("/api/mobile/signals/{signalId}/actions/convert-to-task", signal)
                .header("Authorization", token)
                .header("API-Version", "1"))
        .andExpect(status().isCreated());

    mockMvc
        .perform(
            post("/api/mobile/signals/{signalId}/actions/dismiss", signal)
                .header("Authorization", token)
                .header("API-Version", "1"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("SIGNAL_INVALID_STATE"));
  }

  @Test
  @DisplayName("action 요청에서 workspace 멤버가 아니면 AUTH_FORBIDDEN을 반환한다")
  void actionsReturnForbiddenWhenNotMember() throws Exception {
    UserProfile gyuil =
        userService.findOrCreate("signals-it-action-owner@momens.works", "김규일", null);
    UserProfile jinsu =
        userService.findOrCreate("signals-it-action-stranger@momens.works", "신진수", null);
    UUID workspace = insertWorkspace("signals-action-forbidden");
    addMember(workspace, gyuil.id(), "owner");
    UUID project = insertProject(workspace, gyuil.id(), "signals-action-forbidden-project");
    UUID signal = insertSignal(workspace, project, "risk", "제목", null, null);

    mockMvc
        .perform(
            post("/api/mobile/signals/{signalId}/actions/dismiss", signal)
                .header("Authorization", "Bearer " + accessTokens.issueAccessToken(jinsu.id()))
                .header("API-Version", "1"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("AUTH_FORBIDDEN"));
  }

  @Test
  @DisplayName("없는 Signal에 action을 요청하면 SIGNAL_NOT_FOUND를 반환한다")
  void actionsReturnNotFoundForUnknownSignal() throws Exception {
    UserProfile caller =
        userService.findOrCreate("signals-it-action-404@momens.works", "신진수", null);

    mockMvc
        .perform(
            post("/api/mobile/signals/{signalId}/actions/dismiss", UUID.randomUUID())
                .header("Authorization", "Bearer " + accessTokens.issueAccessToken(caller.id()))
                .header("API-Version", "1"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("SIGNAL_NOT_FOUND"));
  }

  @Test
  @DisplayName("토큰 없이 action을 요청하면 AUTH_UNAUTHORIZED를 반환한다")
  void actionsReturnUnauthorizedWithoutToken() throws Exception {
    mockMvc
        .perform(
            post("/api/mobile/signals/{signalId}/actions/dismiss", UUID.randomUUID())
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

  private UUID insertSignal(
      UUID workspaceId,
      UUID projectId,
      String type,
      String title,
      String impact,
      String minsuSuggestion) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO signals (id, workspace_id, project_id, type, title, description, impact,"
            + " minsu_suggestion) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        id,
        workspaceId,
        projectId,
        type,
        title,
        "본문",
        impact,
        minsuSuggestion);
    return id;
  }

  private void insertAction(UUID workspaceId, UUID signalId, UUID processedByUserId) {
    jdbcTemplate.update(
        "INSERT INTO signal_actions (id, workspace_id, signal_id, action_type,"
            + " processed_by_user_id) VALUES (?, ?, ?, ?, ?)",
        UUID.randomUUID(),
        workspaceId,
        signalId,
        "dismiss",
        processedByUserId);
  }

  private UUID insertSourceRef(
      UUID workspaceId, String sourceType, String title, String snippet, String text, String url) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO source_refs (id, workspace_id, source_type, source_object_type,"
            + " source_object_id, title, snippet, text, source_url)"
            + " VALUES (?, ?, ?, 'FILE_COMMENT', 'obj-1', ?, ?, ?, ?)",
        id,
        workspaceId,
        sourceType,
        title,
        snippet,
        text,
        url);
    return id;
  }

  private void insertEvidence(
      UUID workspaceId,
      UUID signalId,
      UUID sourceRefId,
      int sortOrder,
      String target,
      String change,
      String impact) {
    jdbcTemplate.update(
        "INSERT INTO signal_evidence (workspace_id, signal_id, source_ref_id, sort_order, target,"
            + " \"change\", impact) VALUES (?, ?, ?, ?, ?, ?, ?)",
        workspaceId,
        signalId,
        sourceRefId,
        sortOrder,
        target,
        change,
        impact);
  }
}
