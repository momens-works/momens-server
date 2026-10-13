package works.momens.server.minsu.draft.ledger;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 반영 창이 닫힌 미종료 원장을 닫는 주기.
 *
 * <p><b>{@link MinsuDrainScheduler}와 달리 drain 축에 걸지 않는다.</b> 축을 끈 뒤 남은 {@code pending}이 cutoff를 지나는
 * 것이 바로 이 절차가 필요한 상황이라, 같은 조건을 걸면 정작 그때 돌지 않는다. 축이 켜져 있어도 scheduler가 밀리면 같은 일이 생긴다.
 *
 * <p>주기가 1분인 것은 이 값이 <b>지표의 정확도 상한</b>일 뿐이기 때문이다. cutoff를 지난 행이 gauge에 잡혀 있는 시간이 최대 그만큼이고, 사용자가 보는
 * 것에는 영향이 없다. 읽기 투영은 원장이 아니라 {@code read_deadline_at} 비교로 판정하므로 이 주기와 무관하게 {@code ready}를 돌려준다.
 */
@Slf4j
@Component
class MinsuAbandonedGenerationScheduler {

  /**
   * 한 주기에 닫을 수.
   *
   * <p>rollback 직후에는 적재된 원장이 통째로 대상이 될 수 있다. 한 트랜잭션에 몰면 그만큼 행을 오래 잠그므로 나눠서 처리하고, 남은 것은 다음 주기가
   * 이어받는다. 대상이 없으면 쿼리 하나로 끝난다.
   */
  private static final int BATCH_SIZE = 100;

  private final TaskDraftGenerationLedger ledger;

  MinsuAbandonedGenerationScheduler(TaskDraftGenerationLedger ledger) {
    this.ledger = ledger;
  }

  @Scheduled(fixedDelayString = "1m")
  void closeAbandoned() {
    try {
      int closed = ledger.closeAbandoned(BATCH_SIZE);
      if (closed > 0) {
        // 정상 운영에서는 나오지 않는 로그다. 반영 창 안에 처리되지 못한 원장이 있었다는 뜻이라
        // drain이 멈췄거나 축이 꺼져 있었다는 신호다.
        log.info("반영 창이 닫힌 Minsu 원장 {}건을 종료했습니다", closed);
      }
    } catch (RuntimeException e) {
      // 이번 주기 실패를 기록하고 다음 주기에 재시도한다.
      log.error("Minsu 원장 종료 주기 실패", e);
    }
  }
}
