package works.momens.server.mobile.brief;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class BriefDayTest {

  @Test
  void todayReturnsKstDayAsUtcRange() {
    // 2026-07-10T05:00Z는 KST로 2026-07-10 14:00이다.
    Clock clock = Clock.fixed(Instant.parse("2026-07-10T05:00:00Z"), ZoneOffset.UTC);

    BriefDay.Range range = BriefDay.rangeOf(BriefDay.today(clock));

    // KST 2026-07-10 00:00 = UTC 2026-07-09 15:00, 다음 날 0시 = UTC 2026-07-10 15:00.
    assertThat(range.from()).isEqualTo(Instant.parse("2026-07-09T15:00:00Z"));
    assertThat(range.toExclusive()).isEqualTo(Instant.parse("2026-07-10T15:00:00Z"));
  }

  @Test
  void todayUsesKstDateNotUtcDateNearMidnight() {
    // UTC로는 2026-07-09 22:00이지만 KST로는 이미 2026-07-10 07:00이라 오늘은 07-10이다.
    Clock clock = Clock.fixed(Instant.parse("2026-07-09T22:00:00Z"), ZoneOffset.UTC);

    // 앵커는 UTC 날짜(07-09)가 아니라 KST 날짜(07-10)여야 한다.
    assertThat(BriefDay.today(clock)).isEqualTo(LocalDate.of(2026, 7, 10));

    BriefDay.Range range = BriefDay.rangeOf(BriefDay.today(clock));

    assertThat(range.from()).isEqualTo(Instant.parse("2026-07-09T15:00:00Z"));
    assertThat(range.toExclusive()).isEqualTo(Instant.parse("2026-07-10T15:00:00Z"));
  }
}
