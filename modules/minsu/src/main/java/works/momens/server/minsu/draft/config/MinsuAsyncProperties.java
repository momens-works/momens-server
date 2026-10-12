package works.momens.server.minsu.draft.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * 비동기 생성의 설정 축.
 *
 * <p>축은 셋이고 provider 축은 기존 {@link MinsuTaskDraftProperties#enabled()}가 그대로 담당한다(모델 호출 활성 여부). 여기서는
 * 비동기 도입으로 새로 생기는 두 축과 나이 상한 값을 갖는다.
 *
 * <ul>
 *   <li>{@code enroll} — 신규 convert가 원장을 적재하는가. 끄면 신규는 동기 경로로 가고 기존 원장은 그대로 처리된다.
 *   <li>{@code drain} — scheduler가 원장을 처리하는가. 끄면 기존 원장이 멈추고 {@code read_deadline_at}이 지나면 읽기 투영이
 *       {@code ready}로 닫는다.
 * </ul>
 *
 * <p>축 사이에는 파생 규칙이 하나 있다. <b>provider가 비활성이면 {@code enroll} 값과 무관하게 적재하지 않는다</b>. 운영자가 두 값을 맞춰 끄는
 * 것이 아니라 적재 여부를 {@code provider.enabled && enroll}로 판정해 코드가 강제한다. drain도 같은 방식으로 provider와 함께 판정한다.
 *
 * <p>provider가 활성이지만 <b>설정이 무효한 경우는 적재는 하되 claim하지 않는다</b>. 설정을 고치면 그대로 이어서 처리할 수 있는 작업이므로 종료로 기록하지
 * 않고, 그 전에 {@code read_deadline_at}이 지나면 읽기 투영이 닫는다. 따라서 설정 오류의 1차 관측은 원장이 아니라 {@code
 * momens.minsu.llm.config.valid} gauge다({@link MinsuConfigStatus}). 종료 사유 {@code invalid_config}는
 * claim 이후 설정이 무효해진 경합에서만 도달한다.
 *
 * <p>{@code generationDeadline}과 {@code applyMargin}은 적재 시점에 두 시각으로 환산해 원장에 저장한다. 조회할 때마다 현재 설정으로
 * 다시 계산하면 설정 변경이 과거 작업의 상태를 뒤집으므로, 이 값 변경은 <b>새로 적재되는 원장에만</b> 적용된다.
 *
 * @param generationDeadline 적재 시각 기준 나이 상한. 재시도 백오프 총합보다 충분히 크게 잡는다
 * @param applyMargin 읽기 투영과 반영 CAS 사이의 가드 밴드. 반영 트랜잭션이 조건 평가부터 커밋까지 걸리는 시간보다 커야 한다
 * @param execution scheduler가 원장을 처리할 때의 실행·재시도 정책(MOM-0819)
 */
@Validated
@ConfigurationProperties("momens.minsu.task-draft.async")
public record MinsuAsyncProperties(
    @DefaultValue("false") boolean enroll,
    @DefaultValue("false") boolean drain,
    @DefaultValue("1h") Duration generationDeadline,
    @DefaultValue("5m") Duration applyMargin,
    @DefaultValue Execution execution) {

  /**
   * 두 시각의 순서를 부팅 시점에 강제한다.
   *
   * <p>margin이 0이거나 상한 이상이면 {@code apply_cutoff_at < read_deadline_at} CHECK에 걸려 적재가 전부 실패하는데, 적재는
   * convert 트랜잭션 안이므로 그 실패가 <b>사용자 요청 실패</b>로 처음 드러난다. 값이 잘못됐다는 사실은 요청이 아니라 부팅에서 알아야 한다.
   *
   * <p>실행 값과의 관계도 여기서 이어 붙인다. timeout 부등식 {@code attempt 상한 < lease < apply_cutoff_at <
   * read_deadline_at} 중 앞 구간은 {@link Execution}이, 뒤 구간은 위 두 값이 정하므로 둘을 모두 보는 자리가 이 생성자뿐이다. {@code
   * apply_cutoff_at}은 적재 시각 + {@code generationDeadline} - {@code applyMargin}이므로 lease는 그 차이보다 작아야
   * 한다.
   */
  public MinsuAsyncProperties {
    if (applyMargin.isNegative() || applyMargin.isZero()) {
      throw new IllegalArgumentException("momens.minsu.task-draft.async.apply-margin은 0보다 커야 합니다");
    }
    if (applyMargin.compareTo(generationDeadline) >= 0) {
      throw new IllegalArgumentException(
          "momens.minsu.task-draft.async.apply-margin은 generation-deadline보다 작아야 합니다");
    }
    if (execution.lease().compareTo(generationDeadline.minus(applyMargin)) >= 0) {
      throw new IllegalArgumentException(
          "momens.minsu.task-draft.async.execution.lease는 generation-deadline - apply-margin보다"
              + " 작아야 합니다");
    }
  }

  /**
   * 한 번의 시도와 재시도 정책.
   *
   * <p>값은 모두 <b>잠정값</b>이다. 근거가 되는 실측은 동기 경로의 8초(MOM-0806) 하나뿐이고 그 값은 원탭 action의 사용자 체감 기준이라 비동기의
   * 근거가 되지 못한다. 실제 재측정은 MOM-0824이며 그때까지는 <b>넉넉한 쪽</b>으로 잡았다. 값이 짧으면 정상 호출이 잘려 재시도로 낭비되고 {@code
   * retry_exhausted}가 올라 측정 자체가 오염되는 반면, 길면 {@code generating} 노출이 길어질 뿐이고 그 구간에도 사용자는 유효한 fallback
   * title을 본다.
   *
   * <p>{@code providerTimeout}과 {@code attemptTimeout}이 둘 다 필요한 이유는 SDK timeout이 호출 전체를 덮지 못하기
   * 때문이다. {@code HttpOptions.timeout}은 OkHttp {@code Call} 구간에만 걸리고 ADC 탐색·client 생성·token refresh는
   * 그 밖이다. 전자는 요청별 {@code httpOptions}로 SDK에 걸고, 후자는 시도 전체를 감싸는 wall-clock 상한이다.
   *
   * <p>lease 갱신은 두지 않는다. lease를 비동기 timeout보다 충분히 크게 잡아야 하며 {@code attemptTimeout}의 두 배인 lease가 그것을
   * 충족한다. {@code notification}의 {@code renewLease}는 event 그룹 단위 배치 발송이라 claim과 발송 사이가 벌어지기 때문에
   * 필요했고, minsu는 건당 실행이라 그 간격이 없다.
   *
   * @param providerTimeout SDK에 거는 호출 구간 timeout. 동기 경로의 {@code momens.minsu.llm.timeout}과 별도 키다
   * @param attemptTimeout ADC 탐색·client 생성·token refresh·provider 호출을 모두 감싸는 시도 전체의 wall-clock 상한
   * @param lease claim 소유권 만료까지의 시간. 정상 실행 중 만료가 일어나지 않도록 attempt 상한보다 커야 한다
   * @param maxAttempts 최초 시도를 포함한 총 시도 횟수. 도달하면 {@code retry_exhausted}로 닫는다
   * @param backoffs 실패 후 재시도까지의 대기. {@code push_deliveries}와 같이 마지막 값으로 clamp한다
   * @param concurrency 동시 실행 슬롯 수. 취소되지 않는 호출이 묶을 수 있는 슬롯의 상한이기도 하다
   */
  public record Execution(
      @DefaultValue("20s") Duration providerTimeout,
      @DefaultValue("30s") Duration attemptTimeout,
      @DefaultValue("60s") Duration lease,
      @DefaultValue("4") int maxAttempts,
      @DefaultValue({"10s", "30s", "2m"}) List<Duration> backoffs,
      @DefaultValue("4") int concurrency) {

    /** {@code HttpOptions.timeout}이 밀리초 {@code int}라 이 범위를 벗어나면 SDK로 옮길 수 없다. */
    private static final Duration MIN_SDK_TIMEOUT = Duration.ofMillis(1);

    private static final Duration MAX_SDK_TIMEOUT = Duration.ofMillis(Integer.MAX_VALUE);

    public Execution {
      backoffs = List.copyOf(backoffs);
      // provider-timeout은 SDK로 나가므로 동기 경로와 같은 범위를 요구한다(MinsuConfigStatus).
      // 1ms 미만이면 밀리초 변환에서 0이 되고, 상한을 넘으면 int 변환이 실행 시점에 터진다. 후자는
      // PROVIDER_ERROR로 분류돼 무효한 설정으로 claim·재시도를 반복하는데, 이는 "설정이 무효하면
      // claim하지 않는다"는 규칙과 정면으로 어긋난다. 값이 잘못됐다는 사실은 부팅에서 알아야 한다.
      requireSdkRange(providerTimeout, "provider-timeout");
      requireSdkRange(attemptTimeout, "attempt-timeout");
      requirePositive(lease, "lease");
      // SDK timeout이 wall-clock 상한 이상이면 상한이 먼저 터져 호출을 버리는데, 버려진 호출은 취소되지
      // 않고 슬롯을 계속 점유한다. 같은 값도 막는 이유는 그 경계에서 어느 쪽이 먼저 터질지가
      // 스케줄링에 달리기 때문이다. SDK가 확실히 먼저 끊어야 슬롯이 정상적으로 반환된다.
      if (providerTimeout.compareTo(attemptTimeout) >= 0) {
        throw new IllegalArgumentException(
            "momens.minsu.task-draft.async.execution.provider-timeout은 attempt-timeout보다 작아야 합니다");
      }
      // timeout 부등식의 첫 구간. attempt 상한이 lease보다 길면 정상 실행 중에 lease가 만료돼 다른 worker가
      // 같은 작업을 중복 호출한다.
      if (attemptTimeout.compareTo(lease) >= 0) {
        throw new IllegalArgumentException(
            "momens.minsu.task-draft.async.execution.attempt-timeout은 lease보다 작아야 합니다");
      }
      if (maxAttempts < 1) {
        throw new IllegalArgumentException(
            "momens.minsu.task-draft.async.execution.max-attempts는 1 이상이어야 합니다");
      }
      if (concurrency < 1) {
        throw new IllegalArgumentException(
            "momens.minsu.task-draft.async.execution.concurrency는 1 이상이어야 합니다");
      }
      // 재시도가 있으면 대기도 있어야 한다. 비어 있으면 백오프 조회가 실패 기록 시점에 터지는데, 그때는
      // 이미 claim을 보유한 상태라 그 실패가 lease 만료 회수로만 드러난다.
      if (maxAttempts > 1 && backoffs.isEmpty()) {
        throw new IllegalArgumentException(
            "momens.minsu.task-draft.async.execution.backoffs는 재시도가 있으면 비어 있을 수 없습니다");
      }
      // 0 이하인 백오프는 즉시 재시도가 되어 실패하는 작업이 상한까지 provider를 연달아 때린다.
      // 백오프를 둔 이유가 사라지는데도 동작 자체는 정상으로 보여 관측에서만 드러난다.
      backoffs.forEach(backoff -> requirePositive(backoff, "backoffs"));
    }

    /** 방금 기록한 시도 횟수에 대응하는 재시도 대기. 목록보다 시도가 많으면 마지막 값을 반복한다. */
    public Duration backoffFor(int attemptCount) {
      int index = Math.min(Math.max(attemptCount - 1, 0), backoffs.size() - 1);
      return backoffs.get(index);
    }

    private static void requirePositive(Duration value, String key) {
      if (value.isNegative() || value.isZero()) {
        throw new IllegalArgumentException(
            "momens.minsu.task-draft.async.execution." + key + "은 0보다 커야 합니다");
      }
    }

    /** SDK가 받는 밀리초 {@code int}로 손실 없이 옮길 수 있는 범위. 동기 경로의 검증과 같은 값이다. */
    private static void requireSdkRange(Duration value, String key) {
      if (value.compareTo(MIN_SDK_TIMEOUT) < 0 || value.compareTo(MAX_SDK_TIMEOUT) > 0) {
        throw new IllegalArgumentException(
            "momens.minsu.task-draft.async.execution."
                + key
                + "은 1ms 이상 "
                + MAX_SDK_TIMEOUT.toMillis()
                + "ms 이하여야 합니다");
      }
    }
  }
}
