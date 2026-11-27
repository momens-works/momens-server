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

    // KST 2026-07-10 06:00은 UTC 2026-07-09 21:00에 해당하며, 다음 날 오전 6시는 UTC 2026-07-10 21:00에 해당합니다.
    assertThat(range.from()).isEqualTo(Instant.parse("2026-07-09T21:00:00Z"));
    assertThat(range.toExclusive()).isEqualTo(Instant.parse("2026-07-10T21:00:00Z"));
  }

  @Test
  void todayUsesKstDateNotUtcDateNearMidnight() {
    // UTC로는 2026-07-09 22:00이지만 KST로는 이미 2026-07-10 07:00이라 오늘은 07-10이다.
    Clock clock = Clock.fixed(Instant.parse("2026-07-09T22:00:00Z"), ZoneOffset.UTC);

    // 앵커는 UTC 날짜(07-09)가 아니라 KST 날짜(07-10)여야 한다.
    assertThat(BriefDay.today(clock)).isEqualTo(LocalDate.of(2026, 7, 10));

    BriefDay.Range range = BriefDay.rangeOf(BriefDay.today(clock));

    assertThat(range.from()).isEqualTo(Instant.parse("2026-07-09T21:00:00Z"));
    assertThat(range.toExclusive()).isEqualTo(Instant.parse("2026-07-10T21:00:00Z"));
  }

  @Test
  void todayChangesAtSixAmKst() {
    // UTC 2026-07-09 20:59:59는 KST 2026-07-10 05:59:59이므로 브리프의 기준일은 7월 9일입니다.
    // 1초 뒤인 KST 06:00부터 기준일이 7월 10일로 바뀝니다.
    Clock beforeStart = Clock.fixed(Instant.parse("2026-07-09T20:59:59Z"), ZoneOffset.UTC);
    Clock atStart = Clock.fixed(Instant.parse("2026-07-09T21:00:00Z"), ZoneOffset.UTC);

    assertThat(BriefDay.today(beforeStart)).isEqualTo(LocalDate.of(2026, 7, 9));
    assertThat(BriefDay.today(atStart)).isEqualTo(LocalDate.of(2026, 7, 10));
  }
}
