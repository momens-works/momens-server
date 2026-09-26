package works.momens.server.minsu.draft.generation;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.Locale;
import org.springframework.stereotype.Component;
import works.momens.server.minsu.llm.LlmResponse;
import works.momens.server.minsu.llm.ModelSelection;

@Component
final class MinsuObservability {

  static final String OBSERVATION_NAME = "momens.minsu.llm.generate";

  private final MeterRegistry meterRegistry;
  private final ObservationRegistry observationRegistry;

  MinsuObservability(MeterRegistry meterRegistry, ObservationRegistry observationRegistry) {
    this.meterRegistry = meterRegistry;
    this.observationRegistry = observationRegistry;
    // 사건이 없어도 시계열이 존재하도록 미리 등록한다(지표 규약). 태그 값이 enum에서 오므로 조합이
    // 부팅 시점에 모두 정해진다. 등록하지 않으면 재시작 후 첫 생성 전까지 rate()가 값을 내지 못해
    // 그 구간이 장애와 구분되지 않는다.
    for (GenerationOutcome outcome : GenerationOutcome.values()) {
      requestCounter(outcome);
    }
  }

  /** {@code mode}는 동기 요청 경로와 비동기 실행 경로를 나눠 보기 위한 tag다(설계 9.3절). 값이 둘뿐이라 카디널리티가 늘지 않는다. */
  Observation startProvider(ModelSelection selection, String promptVersion, GenerationMode mode) {
    return Observation.start(OBSERVATION_NAME, observationRegistry)
        .lowCardinalityKeyValue("provider", selection.provider())
        .lowCardinalityKeyValue("model", selection.model())
        .lowCardinalityKeyValue("prompt.version", promptVersion)
        .lowCardinalityKeyValue("mode", mode.tag());
  }

  void completeProvider(
      Observation observation, GenerationOutcome outcome, String finishReason, Throwable error) {
    observation
        .lowCardinalityKeyValue("outcome", outcome.outcome())
        .lowCardinalityKeyValue("fallback.reason", outcome.reason())
        .lowCardinalityKeyValue("finish.reason", safeFinishReason(finishReason));
    if (error != null) {
      observation.error(error);
    }
    observation.stop();
  }

  void recordRequest(GenerationOutcome outcome) {
    requestCounter(outcome).increment();
  }

  private Counter requestCounter(GenerationOutcome outcome) {
    return meterRegistry.counter(
        "momens.minsu.task.draft.requests",
        "outcome",
        outcome.outcome(),
        "fallback.reason",
        outcome.reason());
  }

  /**
   * {@code total}은 태그 값으로 두지 않는다. {@code type}을 가로질러 합산하면 이중 계상이라 "모든 태그를 가로지른 집계가 의미를 가져야 한다"는 지표
   * 규약(docs/rules/observability.md)에 어긋난다. 합계가 필요하면 집계로 구한다.
   *
   * <p>다만 <b>합으로 복원할 수 있는 것은 tool을 쓰지 않는 지금 구성에서만이다.</b> Google GenAI의 {@code totalTokenCount}는
   * {@code toolUsePromptTokenCount}도 포함하므로, tool을 쓰게 되면 그 몫이 세 값 어디에도 잡히지 않는다. 그때는 {@code
   * tool_use_prompt}를 별도 {@code type}으로 추가해야 한다.
   *
   * <p>{@code provider}·{@code model}이 런타임 선택이라 이 지표는 규약의 "0 선등록" 대상이 아니다.
   */
  void recordTokens(ModelSelection selection, LlmResponse.TokenUsage usage) {
    recordToken("prompt", selection, usage.prompt());
    recordToken("candidate", selection, usage.candidate());
    recordToken("thoughts", selection, usage.thoughts());
  }

  private void recordToken(String type, ModelSelection selection, int count) {
    DistributionSummary.builder("momens.minsu.llm.tokens")
        .baseUnit("tokens")
        .tag("provider", selection.provider())
        .tag("model", selection.model())
        .tag("type", type)
        .register(meterRegistry)
        .record(count);
  }

  private static String safeFinishReason(String finishReason) {
    return finishReason == null || finishReason.isBlank()
        ? "none"
        : finishReason.toLowerCase(Locale.ROOT);
  }
}
