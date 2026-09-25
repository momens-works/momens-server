package works.momens.server.mobile.signal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import works.momens.server.auth.AccessTokenTestFactory;
import works.momens.server.common.test.AbstractPostgresIntegrationTest;
import works.momens.server.minsu.DraftStatus;
import works.momens.server.minsu.PreparedTaskDraft;
import works.momens.server.minsu.SignalTaskDraftGenerator;
import works.momens.server.minsu.SignalTaskDraftInput;
import works.momens.server.minsu.TaskDraft;
import works.momens.server.minsu.TaskDraftEnrollmentException;
import works.momens.server.project.task.TaskPriority;
import works.momens.server.project.task.TaskRole;
import works.momens.server.user.UserProfile;
import works.momens.server.user.UserService;

/**
 * convert-to-task가 Minsu draft를 실제로 반영하는지 확인하는 통합 테스트(MOM-0804).
 *
 * <p>Google 호출 없이 배선만 검증하기 위해 {@link SignalTaskDraftGenerator}를 fake로 대체합니다. LLM 호출은 외부 의존이라 실제 발생
 * 여부가 아니라 generator 경계까지의 입력·출력 반영과 replay 시 미호출을 확인합니다. workspace/project/signals/signal_evidence는
 * 생성 public API가 없어 소유 스키마에 SQL로 시드합니다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MobileSignalConvertDraftIntegrationTest extends AbstractPostgresIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private AccessTokenTestFactory accessTokens;
  @Autowired private UserService userService;
  @Autowired private JdbcTemplate jdbcTemplate;

  @MockitoBean private SignalTaskDraftGenerator taskDraftGenerator;

  @Test
  @DisplayName("생성된 draft의 title·role·priority로 태스크를 만들고 evidence를 입력으로 전달한다")
  void convertToTaskAppliesGeneratedDraft() throws Exception {
    UserProfile jinsu = userService.findOrCreate("convert-draft-it@momens.works", "신진수", null);
    UUID workspace = insertWorkspace("convert-draft");
    addMember(workspace, jinsu.id(), "owner");
    UUID project = insertProject(workspace, jinsu.id(), "convert-draft-project");
    UUID signal =
        insertSignal(workspace, project, "decision", "결제 정책 결정 3일째 보류", "합의가 지연됩니다.", "출시 일정에 영향");
    insertEvidence(workspace, signal, 0, "결제 정책", "논의 중단", "출시 지연");
    when(taskDraftGenerator.prepare(any()))
        .thenReturn(prepared(new TaskDraft("결제 정책 확정하기", TaskRole.BACKEND, TaskPriority.HIGH)));
    // 동기 경로라 적재하지 않는다. 원장 행이 없으므로 응답도 replay도 ready다(설계 7.1절).
    when(taskDraftGenerator.enroll(any(), any(), any())).thenReturn(DraftStatus.READY);
    String token = "Bearer " + accessTokens.issueAccessToken(jinsu.id());

    mockMvc
        .perform(
            post("/api/mobile/signals/{signalId}/actions/convert-to-task", signal)
                .header("Authorization", token)
                .header("API-Version", "1"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.task.title").value("결제 정책 확정하기"))
        .andExpect(jsonPath("$.task.status").value("todo"))
        .andExpect(jsonPath("$.task.draft_status").value("ready"));

    assertThat(
            jdbcTemplate.queryForMap(
                "SELECT title, role, priority FROM tasks WHERE project_id = ?", project))
        .containsEntry("title", "결제 정책 확정하기")
        .containsEntry("role", "backend")
        .containsEntry("priority", "high");

    ArgumentCaptor<SignalTaskDraftInput> captor =
        ArgumentCaptor.forClass(SignalTaskDraftInput.class);
    verify(taskDraftGenerator).prepare(captor.capture());
    assertThat(captor.getValue())
        .isEqualTo(
            new SignalTaskDraftInput(
                "결제 정책 결정 3일째 보류",
                "decision",
                "합의가 지연됩니다.",
                "출시 일정에 영향",
                List.of(new SignalTaskDraftInput.Evidence("결제 정책", "논의 중단", "출시 지연"))));

    // replay는 generator를 다시 호출하지 않고 기존 task를 200으로 되돌린다.
    mockMvc
        .perform(
            post("/api/mobile/signals/{signalId}/actions/convert-to-task", signal)
                .header("Authorization", token)
                .header("API-Version", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.task.title").value("결제 정책 확정하기"))
        .andExpect(jsonPath("$.task.draft_status").value("ready"));

    verify(taskDraftGenerator, times(1)).prepare(any());
  }

  @Test
  @DisplayName("원장 적재가 실패하면 convert 전체가 롤백되어 task도 signal_actions도 남지 않는다")
  void rollsBackWholeConvertWhenLedgerEnrollmentFails() throws Exception {
    // fail-closed(5.3절). 원장 없이 응답만 성공하면 그 task는 조용히 풍부화되지 않고 아무도 알 수 없다.
    UserProfile jinsu = userService.findOrCreate("convert-draft-fail-it@momens.works", "신진수", null);
    UUID workspace = insertWorkspace("convert-draft-fail");
    addMember(workspace, jinsu.id(), "owner");
    UUID project = insertProject(workspace, jinsu.id(), "convert-draft-fail-project");
    UUID signal = insertSignal(workspace, project, "risk", "결제 실패율이 올라감", "카드 결제 실패", "전환율 하락");
    when(taskDraftGenerator.prepare(any()))
        .thenReturn(prepared(new TaskDraft("제목", TaskRole.PM, TaskPriority.MEDIUM)));
    doThrow(new TaskDraftEnrollmentException("원장 적재 실패", new RuntimeException()))
        .when(taskDraftGenerator)
        .enroll(any(), any(), any());

    mockMvc
        .perform(
            post("/api/mobile/signals/{signalId}/actions/convert-to-task", signal)
                .header("Authorization", "Bearer " + accessTokens.issueAccessToken(jinsu.id()))
                .header("API-Version", "1"))
        .andExpect(status().is5xxServerError());

    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tasks WHERE project_id = ?", Integer.class, project))
        .isZero();
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM signal_actions WHERE signal_id = ?", Integer.class, signal))
        .isZero();
  }

  @Test
  @DisplayName("dismiss는 draft generator를 호출하지 않는다")
  void dismissDoesNotCallGenerator() throws Exception {
    UserProfile jinsu =
        userService.findOrCreate("convert-draft-dismiss-it@momens.works", "신진수", null);
    UUID workspace = insertWorkspace("convert-draft-dismiss");
    addMember(workspace, jinsu.id(), "owner");
    UUID project = insertProject(workspace, jinsu.id(), "convert-draft-dismiss-project");
    UUID signal = insertSignal(workspace, project, "risk", "제목", "설명", "영향");

    mockMvc
        .perform(
            post("/api/mobile/signals/{signalId}/actions/dismiss", signal)
                .header("Authorization", "Bearer " + accessTokens.issueAccessToken(jinsu.id()))
                .header("API-Version", "1"))
        .andExpect(status().isOk());

    verify(taskDraftGenerator, never()).prepare(any());
  }

  /** 실제 준비 결과는 Minsu 내부 타입이라 밖에서 만들 수 없다. 이 테스트는 generator 경계까지만 본다. */
  private static PreparedTaskDraft prepared(TaskDraft draft) {
    return () -> draft;
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
      String description,
      String impact) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO signals (id, workspace_id, project_id, type, title, description, impact,"
            + " occurred_at) VALUES (?, ?, ?, ?, ?, ?, ?, now())",
        id,
        workspaceId,
        projectId,
        type,
        title,
        description,
        impact);
    return id;
  }

  private void insertEvidence(
      UUID workspaceId, UUID signalId, int sortOrder, String target, String change, String impact) {
    jdbcTemplate.update(
        "INSERT INTO signal_evidence (workspace_id, signal_id, source_ref_id, sort_order, target,"
            + " \"change\", impact) VALUES (?, ?, ?, ?, ?, ?, ?)",
        workspaceId,
        signalId,
        UUID.randomUUID(),
        sortOrder,
        target,
        change,
        impact);
  }
}
