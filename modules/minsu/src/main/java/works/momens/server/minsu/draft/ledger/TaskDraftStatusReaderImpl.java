package works.momens.server.minsu.draft.ledger;

import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import works.momens.server.minsu.DraftStatus;
import works.momens.server.minsu.TaskDraftStatusReader;

/**
 * 읽기 시점 deadline 투영.
 *
 * <p>판정은 DB 한 번의 비교로 끝난다. 상한을 코드에서 계산하지 않는 이유는 원장에 저장된 {@code read_deadline_at}과 같은 시계를 써야 하기 때문이다.
 * 애플리케이션 시계로 비교하면 인스턴스 간 시계 편차가 그대로 상태 판정 편차가 된다.
 */
@Component
class TaskDraftStatusReaderImpl implements TaskDraftStatusReader {

  private final TaskDraftGenerationRepository repository;
  private final MinsuLedgerObservability observability;

  TaskDraftStatusReaderImpl(
      TaskDraftGenerationRepository repository, MinsuLedgerObservability observability) {
    this.repository = repository;
    this.observability = observability;
  }

  /**
   * 공개 계약은 그대로 {@code generating}과 {@code ready} 둘이다. 내부에서만 {@code ready}의 두 경로를 가른다.
   *
   * <p>미종료 원장이 없어서 {@code ready}인 경우와 deadline이 지나 투영이 닫은 경우는 앱에게 같지만 운영에서는 전혀 다르다. 뒤쪽은 생성이 제때 끝나지
   * 않았다는 뜻이고 0이 정상이다. 그래서 그 경로에서만 counter를 올린다.
   */
  @Override
  @Transactional(readOnly = true)
  public DraftStatus statusOf(UUID taskId) {
    return repository
        .generationWindowOpen(taskId)
        .map(windowOpen -> windowOpen ? DraftStatus.GENERATING : closedByDeadline())
        .orElse(DraftStatus.READY);
  }

  private DraftStatus closedByDeadline() {
    observability.recordDeadlineProjection();
    return DraftStatus.READY;
  }
}
