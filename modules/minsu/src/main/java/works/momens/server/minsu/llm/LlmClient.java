package works.momens.server.minsu.llm;

import java.time.Duration;

public interface LlmClient {

  /**
   * 모델을 호출한다.
   *
   * <p>{@code timeout}을 요청마다 받는 이유는 동기 경로와 비동기 경로가 서로 다른 값을 쓰기 때문이다. 동기는 원탭 action의 사용자 체감으로 정한
   * 8초이고, 비동기는 아무도 기다리지 않으므로 더 크다. 두 경로가 하나의 client-level 설정을 공유하면 한쪽을 늘릴 때 다른 쪽의 사용자 체감이 함께 나빠진다.
   */
  LlmResponse generate(ModelSelection selection, LlmRequest request, Duration timeout);
}
