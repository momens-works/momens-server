package works.momens.server.signal;

import java.util.UUID;
import works.momens.server.minsu.DraftStatus;

/**
 * Signal action 처리 결과.
 *
 * <p>{@code created}는 이번 요청으로 새로 처리됐는지(false면 기존 결과를 그대로 반환한 멱등 replay)를 나타낸다. {@code task}는
 * dismiss일 때 항상 {@code null}이다.
 */
public record SignalActionResult(
    UUID signalId, String actionType, boolean created, TaskResult task) {

  /**
   * convert 결과 task.
   *
   * <p>{@code draftStatus}는 Minsu 원장이 정하는 값이고 signal은 매핑하지 않는다. 신규 convert에서는 적재가 돌려준 값을, replay에서는
   * {@code title}·{@code status}를 읽기 <b>전에</b> 조회한 값을 담는다.
   */
  public record TaskResult(UUID id, String title, String status, DraftStatus draftStatus) {}
}
