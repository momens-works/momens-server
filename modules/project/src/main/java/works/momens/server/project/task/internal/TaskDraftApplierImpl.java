package works.momens.server.project.task.internal;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import works.momens.server.project.task.ApplyTaskDraftCommand;
import works.momens.server.project.task.TaskDraftApplier;
import works.momens.server.project.task.TaskDraftApplyResult;
import works.momens.server.project.task.TaskDraftValues;

@Service
@RequiredArgsConstructor
class TaskDraftApplierImpl implements TaskDraftApplier {

  private final TaskRepository taskRepository;

  /**
   * 호출자 트랜잭션에 합류합니다({@code Propagation.MANDATORY}). 여기에 별도 경계를 만들면 호출자의 원장 종료와 원자성이 깨집니다. 반영만 커밋되고
   * 원장이 {@code processing}으로 남으면 이후 재시도가 자기 자신이 쓴 값을 baseline 불일치로 보고 사용자 편집으로 오분류하며, 그 종료 사유는 운영
   * 판단에 쓰입니다. 기본 전파는 트랜잭션이 없을 때 스스로 열어 이 상황을 허용하므로 쓰지 않습니다. 호출자 트랜잭션을 요구하는 모듈 간 협력자라는 점에서 {@code
   * OutboxAppender}·{@code LabelAllocator}와 같은 방식입니다.
   *
   * <p>조건 검사와 갱신을 native bulk update가 아니라 <b>행 잠금 후 엔티티 변경</b>으로 합니다. bulk update는 JPA Auditing을
   * 우회해 {@code tasks.updated_at}이 갱신되지 않고, 그러면 수정 테이블의 {@code updated_at}을 Auditing으로 관리하는
   * 규칙(persistence 규칙)과 어긋납니다. 잠금은 조건 검사와 갱신 사이에 사용자 편집이 끼어드는 것도 함께 막습니다.
   *
   * <p>일반 편집은 버전으로 충돌을 검사합니다. draft가 먼저 커밋되면 이전 버전을 읽은 편집은 충돌로 롤백됩니다.
   */
  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public TaskDraftApplyResult apply(ApplyTaskDraftCommand command) {
    Task task = taskRepository.lockByIdAndDeletedAtIsNull(command.taskId()).orElse(null);
    if (task == null) {
      // 없는 것과 삭제된 것을 구분하지 않습니다. 어느 쪽이든 반영할 대상이 없고, 호출자가 취할 행동도 같습니다.
      return TaskDraftApplyResult.TASK_GONE;
    }
    TaskDraftValues baseline = command.baseline();
    if (!task.matchesDraft(baseline)) {
      return TaskDraftApplyResult.BASELINE_MISMATCH;
    }
    TaskDraftValues draft = command.draft();
    task.applyDraft(draft);
    return TaskDraftApplyResult.APPLIED;
  }
}
