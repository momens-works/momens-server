package works.momens.server.mobile.brief;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/** 브리프의 하루 경계는 Asia/Seoul로 정하고 조회 범위는 UTC로 변환합니다. */
final class BriefDay {

  private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

  private BriefDay() {}

  /** 시그널 created_at을 거를 조회 범위. 끝은 포함하지 않습니다. */
  record Range(Instant from, Instant toExclusive) {}

  /** 지금 시각 기준 오늘(KST)의 날짜입니다. 페이지 세션의 기준일 앵커로 커서에 실어 창을 고정합니다. */
  static LocalDate today(Clock clock) {
    return LocalDate.now(clock.withZone(ZONE));
  }

  /** 주어진 날짜(KST)의 시작과 다음 날 0시를 UTC Instant 범위로 계산합니다. */
  static Range rangeOf(LocalDate date) {
    Instant from = date.atStartOfDay(ZONE).toInstant();
    Instant toExclusive = date.plusDays(1).atStartOfDay(ZONE).toInstant();
    return new Range(from, toExclusive);
  }
}
