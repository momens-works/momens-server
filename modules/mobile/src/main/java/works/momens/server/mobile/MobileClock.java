package works.momens.server.mobile;

import java.time.Clock;

/** 다른 모듈의 {@link Clock} 주입과 충돌하지 않도록 전용 타입으로 감쌉니다. 통합 테스트는 이 공개 타입을 교체해 브리프의 현재 시각을 고정합니다. */
public class MobileClock {

  private final Clock clock;

  public MobileClock(Clock clock) {
    this.clock = clock;
  }

  public Clock clock() {
    return clock;
  }
}
