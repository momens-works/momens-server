package works.momens.server.signal.action;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import works.momens.server.common.api.BusinessException;
import works.momens.server.common.api.CommonErrorCode;
import works.momens.server.minsu.DraftStatus;
import works.momens.server.minsu.PreparedTaskDraft;
import works.momens.server.minsu.SignalTaskDraftGenerator;
import works.momens.server.minsu.SignalTaskDraftInput;
import works.momens.server.minsu.TaskDraftStatusReader;
import works.momens.server.project.task.TaskDetail;
import works.momens.server.project.task.TaskReader;
import works.momens.server.signal.SignalActionResult;
import works.momens.server.signal.SignalActionService;
import works.momens.server.signal.SignalErrorCode;
import works.momens.server.signal.SignalReader;
import works.momens.server.workspace.membership.WorkspaceMembershipReader;

/**
 * Signal action 멱등·충돌 정책 facade.
 *
 * <p>실제 원자 쓰기는 {@link SignalActionExecutor}에 위임한다({@code @Transactional} 프록시가 걸리도록 빈을 분리). {@code
 * signal_actions UNIQUE(signal_id)}로 인한 동시성 레이스(같은 Signal에 대한 두 요청이 동시에 처리 없음을 확인하고 둘 다 insert를
 * 시도하는 경우)는 {@link DataIntegrityViolationException}을 잡아 재조회 후 replay/충돌로 되돌린다. Minsu 생성 원장 적재 실패는 이
 * 경합이 아니라 신규 처리 실패이므로 별도 타입({@code TaskDraftEnrollmentException})으로 이 catch를 지나 그대로 전파된다. {@code
 * action_type} 문자열은 {@link SignalActionType}이 유일한 출처다.
 */
@Service
@RequiredArgsConstructor
class SignalActionServiceImpl implements SignalActionService {

  private final SignalReader signalReader;
  private final WorkspaceMembershipReader workspaceMembershipReader;
  private final SignalActionRepository signalActionRepository;
  private final SignalActionExecutor executor;
  private final TaskReader taskReader;
  private final SignalTaskDraftGenerator taskDraftGenerator;
  private final TaskDraftStatusReader taskDraftStatusReader;

  @Override
  public SignalActionResult convertToTask(UUID signalId, UUID userId) {
    SignalReader.Snapshot signal = loadAuthorized(signalId, userId);
    Optional<SignalAction> existing = signalActionRepository.findBySignalId(signalId);
    if (existing.isPresent()) {
      return replayOrConflict(existing.get(), SignalActionType.CONVERT_TO_TASK);
    }

    PreparedTaskDraft prepared = prepareDraft(signal);
    try {
      return executor.convert(signal, userId, prepared);
    } catch (DataIntegrityViolationException raced) {
      return replayOrConflict(
          signalActionRepository.findBySignalId(signalId).orElseThrow(() -> raced),
          SignalActionType.CONVERT_TO_TASK);
    }
  }

  @Override
  public SignalActionResult dismiss(UUID signalId, UUID userId) {
    SignalReader.Snapshot signal = loadAuthorized(signalId, userId);
    Optional<SignalAction> existing = signalActionRepository.findBySignalId(signalId);
    if (existing.isPresent()) {
      return replayOrConflict(existing.get(), SignalActionType.DISMISS);
    }

    try {
      return executor.dismiss(signal, userId);
    } catch (DataIntegrityViolationException raced) {
      return replayOrConflict(
          signalActionRepository.findBySignalId(signalId).orElseThrow(() -> raced),
          SignalActionType.DISMISS);
    }
  }

  private SignalReader.Snapshot loadAuthorized(UUID signalId, UUID userId) {
    SignalReader.Snapshot signal =
        signalReader
            .findLive(signalId)
            .orElseThrow(
                () ->
                    new BusinessException(
                        SignalErrorCode.SIGNAL_NOT_FOUND,
                        Map.of("signal_id", signalId.toString())));
    if (workspaceMembershipReader.roleOf(signal.workspaceId(), userId).isEmpty()) {
      throw new BusinessException(
          CommonErrorCode.AUTH_FORBIDDEN, Map.of("signal_id", signalId.toString()));
    }
    return signal;
  }

  /**
   * task에 쓸 draft를 확보한다(MOM-0804·MOM-0818). evidence 조회와 모델 호출은 신규 convert에서만 수행하고,
   * replay·충돌·dismiss 경로는 여기까지 오지 않는다. 외부 LLM 호출이 쓰기 트랜잭션과 DB connection을 점유하지 않도록 {@link
   * SignalActionExecutor} 밖에서 호출한다. generator는 실패를 전파하지 않고 항상 유효한 고정 fallback draft를 반환하므로 이 경로에 별도
   * 예외 처리가 없다.
   *
   * <p>비동기 활성 여부는 Minsu가 판정하고 여기서는 알지 못한다. 준비 결과를 그대로 executor에 넘기면 적재는 Minsu가 알아서 한다.
   */
  private PreparedTaskDraft prepareDraft(SignalReader.Snapshot signal) {
    List<SignalTaskDraftInput.Evidence> evidence =
        signalReader.findDraftEvidence(signal.id()).stream()
            .map(
                item ->
                    new SignalTaskDraftInput.Evidence(item.target(), item.change(), item.impact()))
            .toList();
    return taskDraftGenerator.prepare(
        new SignalTaskDraftInput(
            signal.title(), signal.type(), signal.description(), signal.impact(), evidence));
  }

  private SignalActionResult replayOrConflict(
      SignalAction existing, SignalActionType requestedActionType) {
    if (!existing.getActionType().equals(requestedActionType.value())) {
      throw new BusinessException(
          SignalErrorCode.SIGNAL_INVALID_STATE,
          Map.of(
              "signal_id", existing.getSignalId().toString(),
              "processed_action", existing.getActionType()));
    }
    if (requestedActionType == SignalActionType.CONVERT_TO_TASK) {
      // 원장을 task보다 먼저 읽는다(7.3절). 역순이면 fallback title을 읽은 뒤 scheduler가 끝나는 창에서
      // "fallback title + ready"가 나오고, 앱은 재조회를 멈춘 채 그 title에 영구히 갇힌다.
      DraftStatus draftStatus = taskDraftStatusReader.statusOf(existing.getResultTaskId());
      TaskDetail detail =
          taskReader
              .findDetail(existing.getResultTaskId())
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "signal_actions.result_task_id가 존재하지 않는 task를 가리킴: signal_id="
                              + existing.getSignalId()
                              + ", result_task_id="
                              + existing.getResultTaskId()));
      return new SignalActionResult(
          existing.getSignalId(),
          SignalActionType.CONVERT_TO_TASK.value(),
          false,
          new SignalActionResult.TaskResult(
              detail.id(), detail.title(), detail.status(), draftStatus));
    }
    return new SignalActionResult(
        existing.getSignalId(), SignalActionType.DISMISS.value(), false, null);
  }
}
