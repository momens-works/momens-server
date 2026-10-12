package works.momens.server.project.task;

/**
 * AI가 생성한 task draft를 조건부로 반영하는 public API(ADR-0015).
 *
 * <p>{@code minsu}가 비동기로 생성한 draft를 {@code tasks}에 반영합니다. {@code minsu → project} 참조는 이 한 가지 용도로
 * 제한하며, 반대 방향({@code project → minsu})은 순환이라 두지 않습니다.
 *
 * <p><b>호출자 트랜잭션에 참여합니다.</b> 반영과 호출자의 원장 종료가 원자적이어야 하기 때문입니다. 나뉘면 반영 후 프로세스가 죽었을 때 재시도가 baseline
 * 불일치를 보고 사용자 편집으로 오판합니다. 따라서 구현은 {@code REQUIRES_NEW}, 별도 transaction manager, 명시적 조기 commit을 쓰지
 * 않습니다.
 */
public interface TaskDraftApplier {

  /**
   * baseline과 일치할 때만 draft를 반영합니다(compare-and-set).
   *
   * <p>세 필드를 함께 봅니다. 하나라도 다르면 draft 전체를 반영하지 않습니다. 필드별로 나눠 일치하는 것만 반영하면 사용자가 보는 draft가 사람과 모델의 혼합물이
   * 됩니다.
   */
  TaskDraftApplyResult apply(ApplyTaskDraftCommand command);
}
