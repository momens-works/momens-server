package works.momens.server.mobile.brief;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;

final class BriefDay {

  private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
  private static final LocalTime START_TIME = LocalTime.of(6, 0);

  private BriefDay() {}

  /** 시그널 created_at을 거를 조회 범위. */
  record Range(Instant from, Instant toExclusive) {}

  /** 현재 시각이 속한 브리프의 기준일입니다. */
  static LocalDate today(Clock clock) {
    LocalDateTime now = LocalDateTime.now(clock.withZone(ZONE));
    return now.toLocalTime().isBefore(START_TIME)
        ? now.toLocalDate().minusDays(1)
        : now.toLocalDate();
  }

  static Range rangeOf(LocalDate date) {
    Instant from = date.atTime(START_TIME).atZone(ZONE).toInstant();
    Instant toExclusive = date.plusDays(1).atTime(START_TIME).atZone(ZONE).toInstant();
    return new Range(from, toExclusive);
  }
}
