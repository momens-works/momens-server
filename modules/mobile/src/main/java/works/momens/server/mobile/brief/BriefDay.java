package works.momens.server.mobile.brief;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

final class BriefDay {

  private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

  private BriefDay() {}

  /** 시그널 created_at을 거를 조회 범위. */
  record Range(Instant from, Instant toExclusive) {}

  /** 지금 시각 기준 오늘(KST)의 날짜입니다. 페이지 세션의 기준일 앵커로 커서에 실어 창을 고정합니다. */
  static LocalDate today(Clock clock) {
    return LocalDate.now(clock.withZone(ZONE));
  }

  static Range rangeOf(LocalDate date) {
    Instant from = date.atStartOfDay(ZONE).toInstant();
    Instant toExclusive = date.plusDays(1).atStartOfDay(ZONE).toInstant();
    return new Range(from, toExclusive);
  }
}
