package works.momens.server.signal.action;

import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import works.momens.server.minsu.DraftStatus;
import works.momens.server.minsu.PreparedTaskDraft;
import works.momens.server.minsu.SignalTaskDraftGenerator;
import works.momens.server.minsu.TaskDraft;
import works.momens.server.outbox.OutboxAppender;
import works.momens.server.project.task.CreateTaskCommand;
import works.momens.server.project.task.TaskPriority;
import works.momens.server.project.task.TaskRole;
import works.momens.server.project.task.TaskSnapshot;
import works.momens.server.project.task.TaskStatus;
import works.momens.server.project.task.TaskWriter;
import works.momens.server.signal.SignalActionResult;
import works.momens.server.signal.SignalReader;

/**
 * Signal action의 원자 쓰기 트랜잭션.
 *
 * <p>convert-to-task는 {@code tasks insert + signal_actions insert + outbox_events insert(2건: task
 * 생성이 남기는 task.created, 이 메서드가 남기는 signal.converted_to_task)}, dismiss는 {@code signal_actions
 * insert + outbox_events insert(signal.dismissed)}를 한 트랜잭션에 커밋한다(CO-6). facade({@link
 * SignalActionServiceImpl})와 빈을 분리해 self-invocation 없이 {@code @Transactional} 프록시가 걸리게 한다.
 *
 * <p>비동기 생성이 활성이면 여기에 Minsu 생성 원장 적재가 더해진다(MOM-0818, 설계 5.3절). 같은 트랜잭션이므로 롤백되면 원장도 함께 사라지고,
 * scheduler는 커밋된 행만 본다. 반대로 적재가 실패하면 convert 전체가 실패한다(fail-closed).
 */
@Component
@RequiredArgsConstructor
class SignalActionExecutor {

  private static final String AGGREGATE_SIGNAL = "signal";

  private static final String EVENT_SIGNAL_CONVERTED_TO_TASK = "signal.converted_to_task";

  private static final String EVENT_SIGNAL_DISMISSED = "signal.dismissed";

  private final SignalActionRepository signalActionRepository;
  private final TaskWriter taskWriter;
  private final OutboxAppender outboxAppender;
  private final SignalTaskDraftGenerator taskDraftGenerator;

  @Transactional
  SignalActionResult convert(
      SignalReader.Snapshot signal, UUID userId, PreparedTaskDraft prepared) {
    TaskDraft draft = prepared.draft();
    // minsu의 draft 값을 저장에 사용하는 도메인 enum으로 변환합니다. 두 enum의 값이 일치하는지는
    // CheckConstraintEnumConsistencyTest에서 검증합니다.
    TaskSnapshot created =
        taskWriter.create(
            CreateTaskCommand.fromSignal(
                signal.projectId(),
                signal.workspaceId(),
                draft.title(),
                TaskStatus.TODO,
                TaskRole.from(draft.role().value()).orElseThrow(IllegalStateException::new),
                TaskPriority.from(draft.priority().value()).orElseThrow(IllegalStateException::new),
                signal.id()));
    // 원장 baseline은 방금 tasks에 쓴 값이어야 한다(8.1절). task 생성 직후에 적재해 두 값이 갈릴 자리를 두지 않는다.
    // 응답의 draft_status는 이 반환값이고 원장을 다시 읽지 않는다(7.3절). 재조회하면 아래 title(방금 쓴
    // fallback)과 짝이 맞지 않는 ready가 나올 수 있다.
    DraftStatus draftStatus =
        taskDraftGenerator.enroll(prepared, created.id(), signal.workspaceId());
    signalActionRepository.save(
        SignalAction.builder()
            .workspaceId(signal.workspaceId())
            .signalId(signal.id())
            .actionType(SignalActionType.CONVERT_TO_TASK.value())
            .resultTaskId(created.id())
            .processedByUserId(userId)
            .build());
    outboxAppender.append(
        signal.workspaceId(),
        AGGREGATE_SIGNAL,
        signal.id().toString(),
        EVENT_SIGNAL_CONVERTED_TO_TASK,
        Map.of("task_id", created.id().toString()));
    return new SignalActionResult(
        signal.id(),
        SignalActionType.CONVERT_TO_TASK.value(),
        true,
        new SignalActionResult.TaskResult(
            created.id(), created.title(), created.status(), draftStatus));
  }

  @Transactional
  SignalActionResult dismiss(SignalReader.Snapshot signal, UUID userId) {
    signalActionRepository.save(
        SignalAction.builder()
            .workspaceId(signal.workspaceId())
            .signalId(signal.id())
            .actionType(SignalActionType.DISMISS.value())
            .processedByUserId(userId)
            .build());
    outboxAppender.append(
        signal.workspaceId(),
        AGGREGATE_SIGNAL,
        signal.id().toString(),
        EVENT_SIGNAL_DISMISSED,
        Map.of());
    return new SignalActionResult(signal.id(), SignalActionType.DISMISS.value(), true, null);
  }
}
