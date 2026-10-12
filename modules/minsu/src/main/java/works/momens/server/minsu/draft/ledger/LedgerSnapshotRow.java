package works.momens.server.minsu.draft.ledger;

/**
 * 원장 지표 집계 한 행.
 *
 * <p>집계 쿼리라 {@code GROUP BY}가 없어 행이 항상 정확히 하나다. 미종료 행이 없으면 건수는 0, 나이는 {@code COALESCE}로 0이 된다.
 */
interface LedgerSnapshotRow {

  long getPending();

  long getProcessing();

  /** 가장 오래된 미종료 원장의 나이. scheduler 정지를 보는 1차 지표다. {@code pending}만 보면 claim 직후 멈춘 경우를 놓친다. */
  double getOldestUnfinishedAgeSeconds();

  long getExpiredLeases();

  double getExpiredLeaseMaxAgeSeconds();

  /** {@code read_deadline_at}을 지난 미종료 수. deadline 투영 counter는 사용자가 조회해야 올라가므로 이것이 없으면 정지를 늦게 안다. */
  long getReadDeadlineExceeded();
}
