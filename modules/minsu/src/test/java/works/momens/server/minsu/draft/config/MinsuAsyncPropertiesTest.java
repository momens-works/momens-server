package works.momens.server.minsu.draft.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import works.momens.server.minsu.draft.config.MinsuAsyncProperties.Execution;

/**
 * timeout 부등식 {@code attempt 상한 < lease < apply_cutoff_at < read_deadline_at}을 부팅에서 강제하는지 검증한다.
 *
 * <p>이 값들이 어긋났을 때의 실패는 조용하다. attempt 상한이 lease보다 길면 정상 실행 중 중복 호출이 생기고, lease가 apply cutoff보다 길면
 * 회수된 작업이 반영 창을 이미 지난 뒤에 재시도된다. 둘 다 관측에서 뒤늦게 드러나므로 부팅에서 막는다.
 */
class MinsuAsyncPropertiesTest {

  private static final Duration DEADLINE = Duration.ofHours(1);
  private static final Duration MARGIN = Duration.ofMinutes(5);

  @Test
  @DisplayName("잠정 기본값은 부등식을 만족한다")
  void defaultValuesSatisfyTheInequality() {
    MinsuAsyncProperties properties = properties(execution(20, 30, 60));

    Execution execution = properties.execution();
    assertThat(execution.attemptTimeout()).isLessThan(execution.lease());
    assertThat(execution.lease())
        .isLessThan(properties.generationDeadline().minus(properties.applyMargin()));
  }

  @Test
  @DisplayName("attempt 상한이 lease 이상이면 부팅에 실패한다")
  void rejectsAttemptTimeoutNotShorterThanLease() {
    // 같은 값도 막는다. 경계에서 만료와 완료가 동시에 성립하면 중복 호출 여부가 시계 오차에 달린다.
    assertThatThrownBy(() -> execution(20, 60, 60))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("attempt-timeout");
  }

  @Test
  @DisplayName("SDK timeout이 wall-clock 상한보다 크면 부팅에 실패한다")
  void rejectsProviderTimeoutLongerThanAttemptTimeout() {
    // 이 순서가 뒤집히면 상한이 먼저 터져 호출을 버리는데, 버려진 호출은 취소되지 않고 슬롯을 계속
    // 점유한다.
    assertThatThrownBy(() -> execution(40, 30, 60))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("provider-timeout");
  }

  @Test
  @DisplayName("lease가 apply cutoff까지의 간격 이상이면 부팅에 실패한다")
  void rejectsLeaseNotShorterThanApplyCutoffOffset() {
    // lease 만료 회수가 반영 창 밖에서 일어나면 회수해도 반영할 수 없다.
    assertThatThrownBy(() -> properties(execution(20, 30, Duration.ofMinutes(55).toSeconds())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("lease");
  }

  @Test
  @DisplayName("SDK timeout과 wall-clock 상한이 같으면 부팅에 실패한다")
  void rejectsProviderTimeoutEqualToAttemptTimeout() {
    // 같은 값이면 어느 쪽이 먼저 터질지가 스케줄링에 달린다. wall-clock이 먼저 터지면 호출은 취소되지
    // 않은 채 슬롯을 계속 점유한다.
    assertThatThrownBy(() -> execution(30, 30, 60))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("provider-timeout");
  }

  @Test
  @DisplayName("SDK가 받을 수 없는 timeout은 부팅에 실패한다")
  void rejectsTimeoutOutsideSdkRange() {
    // 1ms 미만은 밀리초 변환에서 0이 되고, 상한 초과는 int 변환이 실행 시점에 터진다. 후자는
    // PROVIDER_ERROR로 분류돼 무효한 설정으로 claim과 재시도를 반복하는데, 이는 "설정이 무효하면
    // claim하지 않는다"는 규칙과 어긋난다.
    Duration overflow = Duration.ofMillis(Integer.MAX_VALUE).plusMillis(1);
    assertThatThrownBy(
            () ->
                new Execution(
                    Duration.ofNanos(1),
                    Duration.ofSeconds(30),
                    Duration.ofSeconds(60),
                    4,
                    List.of(Duration.ofSeconds(10)),
                    4))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("provider-timeout");
    assertThatThrownBy(
            () ->
                new Execution(
                    Duration.ofSeconds(20),
                    overflow,
                    overflow.plusSeconds(1),
                    4,
                    List.of(Duration.ofSeconds(10)),
                    4))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("attempt-timeout");
  }

  @Test
  @DisplayName("0 이하인 백오프는 부팅에 실패한다")
  void rejectsNonPositiveBackoff() {
    // 즉시 재시도가 되어 실패하는 작업이 상한까지 provider를 연달아 때린다. 백오프를 둔 이유가
    // 사라지는데도 동작 자체는 정상으로 보인다.
    assertThatThrownBy(
            () ->
                new Execution(
                    Duration.ofSeconds(20),
                    Duration.ofSeconds(30),
                    Duration.ofSeconds(60),
                    4,
                    List.of(Duration.ofSeconds(10), Duration.ZERO),
                    4))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("backoffs");
  }

  @Test
  @DisplayName("재시도가 있는데 백오프 목록이 비어 있으면 부팅에 실패한다")
  void rejectsEmptyBackoffsWhenRetryEnabled() {
    // 비어 있으면 백오프 조회가 실패 기록 시점에 터지고, 그때는 이미 claim을 보유한 상태라 그 실패가
    // lease 만료 회수로만 드러난다.
    assertThatThrownBy(
            () ->
                new Execution(
                    Duration.ofSeconds(20),
                    Duration.ofSeconds(30),
                    Duration.ofSeconds(60),
                    4,
                    List.of(),
                    4))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("backoffs");
  }

  @Test
  @DisplayName("재시도가 없으면 백오프 목록이 비어도 된다")
  void allowsEmptyBackoffsWithoutRetry() {
    assertThatCode(
            () ->
                new Execution(
                    Duration.ofSeconds(20),
                    Duration.ofSeconds(30),
                    Duration.ofSeconds(60),
                    1,
                    List.of(),
                    4))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("백오프는 목록보다 시도가 많으면 마지막 값을 반복한다")
  void clampsBackoffToLastEntry() {
    Execution execution = execution(20, 30, 60);

    assertThat(execution.backoffFor(1)).isEqualTo(Duration.ofSeconds(10));
    assertThat(execution.backoffFor(3)).isEqualTo(Duration.ofMinutes(2));
    // MAX_ATTEMPTS를 늘리고 백오프를 그대로 두는 설정 변경에서도 조회가 터지지 않는다.
    assertThat(execution.backoffFor(9)).isEqualTo(Duration.ofMinutes(2));
  }

  private static MinsuAsyncProperties properties(Execution execution) {
    return new MinsuAsyncProperties(true, true, DEADLINE, MARGIN, execution);
  }

  private static Execution execution(long providerSeconds, long attemptSeconds, long leaseSeconds) {
    return new Execution(
        Duration.ofSeconds(providerSeconds),
        Duration.ofSeconds(attemptSeconds),
        Duration.ofSeconds(leaseSeconds),
        4,
        List.of(Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofMinutes(2)),
        4);
  }
}
