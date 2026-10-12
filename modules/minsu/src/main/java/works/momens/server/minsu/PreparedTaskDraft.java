package works.momens.server.minsu;

/**
 * {@link SignalTaskDraftGenerator#prepare} 결과.
 *
 * <p>호출자가 쓸 수 있는 것은 {@link #draft()}뿐이고, 비동기 활성 여부는 <b>구현 타입에만</b> 담긴다. 구현은 Minsu 내부에만 있으므로 호출자는 값을
 * 읽을 수도, 존재 여부를 물을 수도, 직접 만들 수도 없다. {@link SignalTaskDraftGenerator#enroll}에 그대로 돌려주는 것이 전부다.
 *
 * <p>이 불투명성이 "활성 판정은 요청당 한 번"이라는 불변식을 관례가 아니라 타입으로 강제한다. 판정이 갈리면 중복 생성과 baseline 불일치가 생기거나, LLM을 한
 * 번도 부르지 않은 채 {@code ready}가 된다.
 */
public interface PreparedTaskDraft {

  /** 호출자가 {@code tasks}에 쓰는 draft. 비동기 활성이면 고정 fallback이다. */
  TaskDraft draft();
}
