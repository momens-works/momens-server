package works.momens.server.minsu;

import java.util.UUID;

/**
 * task draft 생성 상태 조회.
 *
 * <p>이 진입점이 <b>deadline 투영의 유일한 소유자</b>다. 조회하는 쪽마다 나이 상한을 각자 계산하면 반드시 어긋나므로, 원장을 읽는 모든 경로는 여기를 통한다.
 */
public interface TaskDraftStatusReader {

  /**
   * task의 draft 상태를 판정한다.
   *
   * <p>원장 행이 없는 task(비동기 도입 이전·비활성·Signal을 거치지 않은 생성)는 {@link DraftStatus#READY}다.
   */
  DraftStatus statusOf(UUID taskId);
}
