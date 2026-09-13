package works.momens.server.signal.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DataIntegrityViolationException;
import works.momens.server.common.api.BusinessException;
import works.momens.server.common.api.CommonErrorCode;
import works.momens.server.minsu.DraftStatus;
import works.momens.server.minsu.PreparedTaskDraft;
import works.momens.server.minsu.Priority;
import works.momens.server.minsu.Role;
import works.momens.server.minsu.SignalTaskDraftGenerator;
import works.momens.server.minsu.SignalTaskDraftInput;
import works.momens.server.minsu.TaskDraft;
import works.momens.server.minsu.TaskDraftEnrollmentException;
import works.momens.server.minsu.TaskDraftStatusReader;
import works.momens.server.project.task.TaskDetail;
import works.momens.server.project.task.TaskReader;
import works.momens.server.signal.SignalActionResult;
import works.momens.server.signal.SignalErrorCode;
import works.momens.server.signal.SignalReader;
import works.momens.server.workspace.membership.WorkspaceMembershipReader;
import works.momens.server.workspace.membership.WorkspaceRole;

/** SignalActionServiceImpl의 가드·멱등·충돌·동시성 레이스 정책 검증(원자 쓰기는 SignalActionExecutor를 mock으로 분리). */
class SignalActionServiceImplTest {

  private static final UUID SIGNAL_ID = UUID.randomUUID();
  private static final UUID WORKSPACE_ID = UUID.randomUUID();
  private static final UUID PROJECT_ID = UUID.randomUUID();
  private static final UUID USER_ID = UUID.randomUUID();

  private final SignalReader signalReader = mock(SignalReader.class);
  private final WorkspaceMembershipReader workspaceMembershipReader =
      mock(WorkspaceMembershipReader.class);
  private final SignalActionRepository signalActionRepository = mock(SignalActionRepository.class);
  private final SignalActionExecutor executor = mock(SignalActionExecutor.class);
  private final TaskReader taskReader = mock(TaskReader.class);
  private final SignalTaskDraftGenerator taskDraftGenerator = mock(SignalTaskDraftGenerator.class);
  private final TaskDraftStatusReader taskDraftStatusReader = mock(TaskDraftStatusReader.class);

  private SignalActionServiceImpl service;

  @BeforeEach
  void setUp() {
    service =
        new SignalActionServiceImpl(
            signalReader,
            workspaceMembershipReader,
            signalActionRepository,
            executor,
            taskReader,
            taskDraftGenerator,
            taskDraftStatusReader);
  }

  /**
   * 비동기 비활성 상태의 준비 결과. 이 테스트가 보는 것은 facade의 가드·멱등·경합 정책이고, 적재 여부 판정은 Minsu가 소유하므로 여기서는 의사가 비어 있는
   * 경우만 쓴다.
   */
  private static PreparedTaskDraft prepared(String title, Role role, Priority priority) {
    return new TestPreparedDraft(new TaskDraft(title, role, priority));
  }

  /** 실제 준비 결과는 Minsu 내부 타입이라 밖에서 만들 수 없다. 적재 여부 판정도 Minsu가 소유한다. */
  private record TestPreparedDraft(TaskDraft draft) implements PreparedTaskDraft {}

  private static SignalReader.Snapshot snapshot() {
    return new SignalReader.Snapshot(
        SIGNAL_ID, WORKSPACE_ID, PROJECT_ID, "decision", "제목", "설명", "전체 영향");
  }

  @Test
  @DisplayName("Signal이 없으면 SIGNAL_NOT_FOUND를 던진다")
  void throwsSignalNotFoundWhenSignalMissing() {
    when(signalReader.findLive(SIGNAL_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.convertToTask(SIGNAL_ID, USER_ID))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(SignalErrorCode.SIGNAL_NOT_FOUND);
  }

  @Test
  @DisplayName("workspace 멤버가 아니면 AUTH_FORBIDDEN을 던진다(404 우선)")
  void throwsForbiddenWhenNotMember() {
    when(signalReader.findLive(SIGNAL_ID)).thenReturn(Optional.of(snapshot()));
    when(workspaceMembershipReader.roleOf(WORKSPACE_ID, USER_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.dismiss(SIGNAL_ID, USER_ID))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(CommonErrorCode.AUTH_FORBIDDEN);
  }

  @Test
  @DisplayName("신규 convert는 generator를 한 번 호출하고 생성된 draft로 executor를 호출한다")
  void convertUsesGeneratedDraft() {
    SignalReader.Snapshot signal = snapshot();
    when(signalReader.findLive(SIGNAL_ID)).thenReturn(Optional.of(signal));
    when(workspaceMembershipReader.roleOf(WORKSPACE_ID, USER_ID))
        .thenReturn(Optional.of(WorkspaceRole.MEMBER));
    when(signalActionRepository.findBySignalId(SIGNAL_ID)).thenReturn(Optional.empty());
    when(signalReader.findDraftEvidence(SIGNAL_ID)).thenReturn(List.of());
    when(taskDraftGenerator.prepare(any()))
        .thenReturn(prepared("결제 정책 확정하기", Role.BACKEND, Priority.HIGH));
    when(executor.convert(signal, USER_ID, prepared("결제 정책 확정하기", Role.BACKEND, Priority.HIGH)))
        .thenReturn(
            new SignalActionResult(
                SIGNAL_ID,
                "convert_to_task",
                true,
                new SignalActionResult.TaskResult(
                    UUID.randomUUID(), "결제 정책 확정하기", "todo", DraftStatus.GENERATING)));

    SignalActionResult result = service.convertToTask(SIGNAL_ID, USER_ID);

    assertThat(result.created()).isTrue();
    verify(taskDraftGenerator, times(1)).prepare(any());
    verify(executor).convert(signal, USER_ID, prepared("결제 정책 확정하기", Role.BACKEND, Priority.HIGH));
  }

  @Test
  @DisplayName("고정 fallback draft도 같은 executor 경로로 전달한다")
  void convertPassesFallbackDraftToExecutor() {
    SignalReader.Snapshot signal = snapshot();
    when(signalReader.findLive(SIGNAL_ID)).thenReturn(Optional.of(signal));
    when(workspaceMembershipReader.roleOf(WORKSPACE_ID, USER_ID))
        .thenReturn(Optional.of(WorkspaceRole.MEMBER));
    when(signalActionRepository.findBySignalId(SIGNAL_ID)).thenReturn(Optional.empty());
    when(signalReader.findDraftEvidence(SIGNAL_ID)).thenReturn(List.of());
    when(taskDraftGenerator.prepare(any())).thenReturn(prepared("제목", Role.PM, Priority.MEDIUM));
    when(executor.convert(signal, USER_ID, prepared("제목", Role.PM, Priority.MEDIUM)))
        .thenReturn(
            new SignalActionResult(
                SIGNAL_ID,
                "convert_to_task",
                true,
                new SignalActionResult.TaskResult(
                    UUID.randomUUID(), "제목", "todo", DraftStatus.GENERATING)));

    SignalActionResult result = service.convertToTask(SIGNAL_ID, USER_ID);

    assertThat(result.created()).isTrue();
    verify(executor).convert(signal, USER_ID, prepared("제목", Role.PM, Priority.MEDIUM));
  }

  @Test
  @DisplayName("generator 입력은 Signal title/type/description/impact와 조회 순서를 유지한 evidence만 포함한다")
  void convertPassesSignalAndEvidenceToGenerator() {
    when(signalReader.findLive(SIGNAL_ID)).thenReturn(Optional.of(snapshot()));
    when(workspaceMembershipReader.roleOf(WORKSPACE_ID, USER_ID))
        .thenReturn(Optional.of(WorkspaceRole.MEMBER));
    when(signalActionRepository.findBySignalId(SIGNAL_ID)).thenReturn(Optional.empty());
    when(signalReader.findDraftEvidence(SIGNAL_ID))
        .thenReturn(
            List.of(
                new SignalReader.DraftEvidence("결제 정책", "논의 중단", "출시 지연"),
                new SignalReader.DraftEvidence("환불 정책", "합의 없음", "CS 부담")));
    when(taskDraftGenerator.prepare(any())).thenReturn(prepared("제목", Role.PM, Priority.MEDIUM));
    when(executor.convert(any(), any(), any()))
        .thenReturn(new SignalActionResult(SIGNAL_ID, "convert_to_task", true, null));

    service.convertToTask(SIGNAL_ID, USER_ID);

    ArgumentCaptor<SignalTaskDraftInput> captor =
        ArgumentCaptor.forClass(SignalTaskDraftInput.class);
    verify(taskDraftGenerator).prepare(captor.capture());
    assertThat(captor.getValue())
        .isEqualTo(
            new SignalTaskDraftInput(
                "제목",
                "decision",
                "설명",
                "전체 영향",
                List.of(
                    new SignalTaskDraftInput.Evidence("결제 정책", "논의 중단", "출시 지연"),
                    new SignalTaskDraftInput.Evidence("환불 정책", "합의 없음", "CS 부담"))));
  }

  @Test
  @DisplayName("근거가 없으면 빈 evidence 목록으로 generator를 호출한다")
  void convertPassesEmptyEvidenceWhenSignalHasNone() {
    when(signalReader.findLive(SIGNAL_ID)).thenReturn(Optional.of(snapshot()));
    when(workspaceMembershipReader.roleOf(WORKSPACE_ID, USER_ID))
        .thenReturn(Optional.of(WorkspaceRole.MEMBER));
    when(signalActionRepository.findBySignalId(SIGNAL_ID)).thenReturn(Optional.empty());
    when(signalReader.findDraftEvidence(SIGNAL_ID)).thenReturn(List.of());
    when(taskDraftGenerator.prepare(any())).thenReturn(prepared("제목", Role.PM, Priority.MEDIUM));
    when(executor.convert(any(), any(), any()))
        .thenReturn(new SignalActionResult(SIGNAL_ID, "convert_to_task", true, null));

    service.convertToTask(SIGNAL_ID, USER_ID);

    ArgumentCaptor<SignalTaskDraftInput> captor =
        ArgumentCaptor.forClass(SignalTaskDraftInput.class);
    verify(taskDraftGenerator).prepare(captor.capture());
    assertThat(captor.getValue().evidence()).isEmpty();
  }

  @Test
  @DisplayName("dismiss는 evidence를 조회하지 않고 generator도 호출하지 않는다")
  void dismissDoesNotTouchDraftGeneration() {
    SignalReader.Snapshot signal = snapshot();
    when(signalReader.findLive(SIGNAL_ID)).thenReturn(Optional.of(signal));
    when(workspaceMembershipReader.roleOf(WORKSPACE_ID, USER_ID))
        .thenReturn(Optional.of(WorkspaceRole.MEMBER));
    when(signalActionRepository.findBySignalId(SIGNAL_ID)).thenReturn(Optional.empty());
    when(executor.dismiss(signal, USER_ID))
        .thenReturn(new SignalActionResult(SIGNAL_ID, "dismiss", true, null));

    service.dismiss(SIGNAL_ID, USER_ID);

    verify(taskDraftGenerator, never()).prepare(any());
    verify(signalReader, never()).findDraftEvidence(any());
  }

  @Test
  @DisplayName("같은 action 재요청은 executor를 호출하지 않고 기존 결과를 replay한다")
  void replaysExistingResultForSameAction() {
    when(signalReader.findLive(SIGNAL_ID)).thenReturn(Optional.of(snapshot()));
    when(workspaceMembershipReader.roleOf(WORKSPACE_ID, USER_ID))
        .thenReturn(Optional.of(WorkspaceRole.MEMBER));
    UUID taskId = UUID.randomUUID();
    SignalAction existing =
        SignalAction.builder()
            .workspaceId(WORKSPACE_ID)
            .signalId(SIGNAL_ID)
            .actionType("convert_to_task")
            .resultTaskId(taskId)
            .processedByUserId(USER_ID)
            .build();
    when(signalActionRepository.findBySignalId(SIGNAL_ID)).thenReturn(Optional.of(existing));
    when(taskReader.findDetail(taskId))
        .thenReturn(
            Optional.of(
                new TaskDetail(
                    taskId,
                    PROJECT_ID,
                    WORKSPACE_ID,
                    "제목",
                    "todo",
                    "medium",
                    "pm",
                    null,
                    null,
                    java.util.List.of(),
                    java.util.List.of(),
                    null)));

    SignalActionResult result = service.convertToTask(SIGNAL_ID, USER_ID);

    assertThat(result.created()).isFalse();
    assertThat(result.task().id()).isEqualTo(taskId);
    verify(executor, never()).convert(any(), any(), any());
    verify(taskDraftGenerator, never()).prepare(any());
    verify(signalReader, never()).findDraftEvidence(any());
  }

  private static SignalAction existingConvert(UUID taskId) {
    return SignalAction.builder()
        .workspaceId(WORKSPACE_ID)
        .signalId(SIGNAL_ID)
        .actionType("convert_to_task")
        .resultTaskId(taskId)
        .processedByUserId(USER_ID)
        .build();
  }

  private static TaskDetail taskDetail(UUID taskId, String title) {
    return new TaskDetail(
        taskId,
        PROJECT_ID,
        WORKSPACE_ID,
        title,
        "todo",
        "medium",
        "pm",
        null,
        null,
        List.of(),
        List.of(),
        null);
  }

  @Test
  @DisplayName("replay는 원장을 task보다 먼저 읽고 그 상태를 응답에 담는다")
  void replayReadsLedgerBeforeTask() {
    when(signalReader.findLive(SIGNAL_ID)).thenReturn(Optional.of(snapshot()));
    when(workspaceMembershipReader.roleOf(WORKSPACE_ID, USER_ID))
        .thenReturn(Optional.of(WorkspaceRole.MEMBER));
    UUID taskId = UUID.randomUUID();
    when(signalActionRepository.findBySignalId(SIGNAL_ID))
        .thenReturn(Optional.of(existingConvert(taskId)));
    when(taskDraftStatusReader.statusOf(taskId)).thenReturn(DraftStatus.GENERATING);
    when(taskReader.findDetail(taskId)).thenReturn(Optional.of(taskDetail(taskId, "제목")));

    SignalActionResult result = service.convertToTask(SIGNAL_ID, USER_ID);

    assertThat(result.task().draftStatus()).isEqualTo(DraftStatus.GENERATING);
    // 역순이면 이전 title을 읽은 뒤 생성이 끝나 "이전 title + ready"가 나가고, 앱은 재조회를 멈춘 채
    // 그 title에 영구히 갇힌다(설계 7.3절).
    InOrder order = inOrder(taskDraftStatusReader, taskReader);
    order.verify(taskDraftStatusReader).statusOf(taskId);
    order.verify(taskReader).findDetail(taskId);
  }

  @Test
  @DisplayName("replay 대상 task가 존재하지 않으면 진단 메시지를 담은 IllegalStateException을 던진다")
  void throwsIllegalStateWhenReplayTaskMissing() {
    when(signalReader.findLive(SIGNAL_ID)).thenReturn(Optional.of(snapshot()));
    when(workspaceMembershipReader.roleOf(WORKSPACE_ID, USER_ID))
        .thenReturn(Optional.of(WorkspaceRole.MEMBER));
    UUID missingTaskId = UUID.randomUUID();
    SignalAction existing =
        SignalAction.builder()
            .workspaceId(WORKSPACE_ID)
            .signalId(SIGNAL_ID)
            .actionType("convert_to_task")
            .resultTaskId(missingTaskId)
            .processedByUserId(USER_ID)
            .build();
    when(signalActionRepository.findBySignalId(SIGNAL_ID)).thenReturn(Optional.of(existing));
    when(taskReader.findDetail(missingTaskId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.convertToTask(SIGNAL_ID, USER_ID))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(SIGNAL_ID.toString())
        .hasMessageContaining(missingTaskId.toString());
  }

  @Test
  @DisplayName("이미 다른 action으로 처리됐으면 SIGNAL_INVALID_STATE를 던진다")
  void throwsInvalidStateWhenProcessedByDifferentAction() {
    when(signalReader.findLive(SIGNAL_ID)).thenReturn(Optional.of(snapshot()));
    when(workspaceMembershipReader.roleOf(WORKSPACE_ID, USER_ID))
        .thenReturn(Optional.of(WorkspaceRole.MEMBER));
    SignalAction existing =
        SignalAction.builder()
            .workspaceId(WORKSPACE_ID)
            .signalId(SIGNAL_ID)
            .actionType("dismiss")
            .processedByUserId(USER_ID)
            .build();
    when(signalActionRepository.findBySignalId(SIGNAL_ID)).thenReturn(Optional.of(existing));

    assertThatThrownBy(() -> service.convertToTask(SIGNAL_ID, USER_ID))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(SignalErrorCode.SIGNAL_INVALID_STATE);
    verify(taskDraftGenerator, never()).prepare(any());
    verify(signalReader, never()).findDraftEvidence(any());
  }

  @Test
  @DisplayName("convertToTask 동시성 레이스로 제약 위반을 만나면 재조회해 기존 task로 replay한다")
  void recoversFromRaceByReplayingAfterConvertConstraintViolation() {
    SignalReader.Snapshot signal = snapshot();
    when(signalReader.findLive(SIGNAL_ID)).thenReturn(Optional.of(signal));
    when(workspaceMembershipReader.roleOf(WORKSPACE_ID, USER_ID))
        .thenReturn(Optional.of(WorkspaceRole.MEMBER));
    UUID taskId = UUID.randomUUID();
    SignalAction racedRow =
        SignalAction.builder()
            .workspaceId(WORKSPACE_ID)
            .signalId(SIGNAL_ID)
            .actionType("convert_to_task")
            .resultTaskId(taskId)
            .processedByUserId(USER_ID)
            .build();
    when(signalActionRepository.findBySignalId(SIGNAL_ID))
        .thenReturn(Optional.empty())
        .thenReturn(Optional.of(racedRow));
    when(signalReader.findDraftEvidence(SIGNAL_ID)).thenReturn(List.of());
    when(taskDraftGenerator.prepare(any())).thenReturn(prepared("제목", Role.PM, Priority.MEDIUM));
    when(executor.convert(signal, USER_ID, prepared("제목", Role.PM, Priority.MEDIUM)))
        .thenThrow(new DataIntegrityViolationException("unique violation"));
    when(taskReader.findDetail(taskId))
        .thenReturn(
            Optional.of(
                new TaskDetail(
                    taskId,
                    PROJECT_ID,
                    WORKSPACE_ID,
                    "제목",
                    "todo",
                    "medium",
                    "pm",
                    null,
                    null,
                    java.util.List.of(),
                    java.util.List.of(),
                    null)));

    SignalActionResult result = service.convertToTask(SIGNAL_ID, USER_ID);

    assertThat(result.created()).isFalse();
    assertThat(result.actionType()).isEqualTo("convert_to_task");
    assertThat(result.task().id()).isEqualTo(taskId);
    verify(signalActionRepository, times(2)).findBySignalId(SIGNAL_ID);
  }

  @Test
  @DisplayName("원장 적재 실패는 경합 흡수 경로로 삼키지 않고 그대로 전파한다")
  void propagatesLedgerEnrollmentFailureInsteadOfReplaying() {
    // 적재 실패는 트랜잭션 전체가 롤백돼 signal_actions도 남지 않는 신규 처리 실패이고, 커밋됐는데
    // 응답만 유실된 replay와 장애 성격이 다르다(5.3절). 둘을 같은 경로로 흡수하면 분석이 어긋난다.
    SignalReader.Snapshot signal = snapshot();
    when(signalReader.findLive(SIGNAL_ID)).thenReturn(Optional.of(signal));
    when(workspaceMembershipReader.roleOf(WORKSPACE_ID, USER_ID))
        .thenReturn(Optional.of(WorkspaceRole.MEMBER));
    when(signalActionRepository.findBySignalId(SIGNAL_ID)).thenReturn(Optional.empty());
    when(signalReader.findDraftEvidence(SIGNAL_ID)).thenReturn(List.of());
    when(taskDraftGenerator.prepare(any())).thenReturn(prepared("제목", Role.PM, Priority.MEDIUM));
    when(executor.convert(any(), any(), any()))
        .thenThrow(new TaskDraftEnrollmentException("원장 적재 실패", new RuntimeException()));

    assertThatThrownBy(() -> service.convertToTask(SIGNAL_ID, USER_ID))
        .isInstanceOf(TaskDraftEnrollmentException.class);
    verify(signalActionRepository, times(1)).findBySignalId(SIGNAL_ID);
  }

  @Test
  @DisplayName("동시성 레이스로 executor가 제약 위반을 던지면 재조회해 replay한다")
  void recoversFromRaceByReplayingAfterConstraintViolation() {
    SignalReader.Snapshot signal = snapshot();
    when(signalReader.findLive(SIGNAL_ID)).thenReturn(Optional.of(signal));
    when(workspaceMembershipReader.roleOf(WORKSPACE_ID, USER_ID))
        .thenReturn(Optional.of(WorkspaceRole.MEMBER));
    SignalAction racedRow =
        SignalAction.builder()
            .workspaceId(WORKSPACE_ID)
            .signalId(SIGNAL_ID)
            .actionType("dismiss")
            .processedByUserId(USER_ID)
            .build();
    when(signalActionRepository.findBySignalId(SIGNAL_ID))
        .thenReturn(Optional.empty())
        .thenReturn(Optional.of(racedRow));
    when(executor.dismiss(signal, USER_ID))
        .thenThrow(new DataIntegrityViolationException("unique violation"));

    SignalActionResult result = service.dismiss(SIGNAL_ID, USER_ID);

    assertThat(result.created()).isFalse();
    assertThat(result.actionType()).isEqualTo("dismiss");
    verify(signalActionRepository, times(2)).findBySignalId(SIGNAL_ID);
  }
}
