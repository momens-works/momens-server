package works.momens.server.minsu.draft.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.ref.Reference;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import works.momens.server.minsu.llm.LlmConfigStatus;

class MinsuConfigStatusTest {

  private static final MinsuTaskDraftProperties.Metrics METRICS =
      new MinsuTaskDraftProperties.Metrics(Duration.ofSeconds(10));

  @Test
  void disabledDraftIsValidRegardlessOfLlmStatus() {
    LlmConfigStatus llmStatus = mock(LlmConfigStatus.class);
    when(llmStatus.providerTag()).thenReturn("google");
    when(llmStatus.modelTag()).thenReturn("gemini-3.5-flash-lite");
    SimpleMeterRegistry registry = new SimpleMeterRegistry();

    MinsuConfigStatus status =
        new MinsuConfigStatus(new MinsuTaskDraftProperties(false, METRICS), llmStatus, registry);

    assertThat(status.enabled()).isFalse();
    assertThat(status.valid()).isTrue();
    assertThat(configValid(registry, status)).isEqualTo(1);
  }

  @Test
  void enabledDraftUsesLlmStatus() {
    LlmConfigStatus llmStatus = mock(LlmConfigStatus.class);
    when(llmStatus.valid()).thenReturn(false);
    when(llmStatus.providerTag()).thenReturn("google");
    when(llmStatus.modelTag()).thenReturn("gemini-3.5-flash-lite");
    SimpleMeterRegistry registry = new SimpleMeterRegistry();

    MinsuConfigStatus status =
        new MinsuConfigStatus(new MinsuTaskDraftProperties(true, METRICS), llmStatus, registry);

    assertThat(status.enabled()).isTrue();
    assertThat(status.valid()).isFalse();
    assertThat(configValid(registry, status)).isZero();
  }

  // gauge는 관찰 대상 객체를 weak reference로만 참조하므로, 객체가 GC되면 NaN을 반환합니다.
  // 객체를 지역 변수에 할당해도 마지막 사용 이후에는 GC될 수 있으므로 이것만으로는 충분하지 않습니다.
  // 따라서 관찰 대상 객체를 인자로 받고, gauge 값을 읽은 직후 reachabilityFence를 호출해
  // 이 시점까지 객체가 strongly reachable한 상태를 유지합니다.
  private static double configValid(SimpleMeterRegistry registry, MinsuConfigStatus status) {
    double value = registry.get("momens.minsu.llm.config.valid").gauge().value();
    Reference.reachabilityFence(status);
    return value;
  }
}
