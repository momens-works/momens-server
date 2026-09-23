package works.momens.server.minsu.llm.google;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.genai.errors.GenAiIOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import works.momens.server.minsu.llm.LlmRequest;
import works.momens.server.minsu.llm.LlmResponse;
import works.momens.server.minsu.llm.LlmTimeoutException;
import works.momens.server.minsu.llm.ModelSelection;

class GoogleGenAiLlmClientTest {

  private static final ModelSelection SELECTION =
      new ModelSelection("google", "gemini-3.5-flash-lite", "project", "global");
  private static final LlmRequest REQUEST = new LlmRequest("v1", "system", "{}", List.of());
  private static final Duration TIMEOUT = Duration.ofSeconds(8);

  @Test
  void reusesOneSuccessfulClientAcrossConcurrentCallsAndClosesIt() throws Exception {
    AtomicInteger creates = new AtomicInteger();
    FakeGoogleSdkClient sdkClient = new FakeGoogleSdkClient();
    GoogleGenAiLlmClient client =
        new GoogleGenAiLlmClient(
            selection -> {
              creates.incrementAndGet();
              return sdkClient;
            });
    List<Callable<LlmResponse>> calls = new ArrayList<>();
    for (int index = 0; index < 30; index++) {
      calls.add(() -> client.generate(SELECTION, REQUEST, TIMEOUT));
    }

    try (var executor = Executors.newFixedThreadPool(8)) {
      executor
          .invokeAll(calls)
          .forEach(future -> assertThat(future).succeedsWithin(Duration.ofSeconds(1)));
    }
    client.close();

    assertThat(creates).hasValue(1);
    assertThat(sdkClient.generates).hasValue(30);
    assertThat(sdkClient.closes).hasValue(1);
  }

  @Test
  void doesNotCacheFailedCreationAndRetriesOnNextRequest() {
    AtomicInteger creates = new AtomicInteger();
    FakeGoogleSdkClient sdkClient = new FakeGoogleSdkClient();
    GoogleGenAiLlmClient client =
        new GoogleGenAiLlmClient(
            selection -> {
              if (creates.incrementAndGet() == 1) {
                throw new IllegalStateException("ADC unavailable");
              }
              return sdkClient;
            });

    assertThatThrownBy(() -> client.generate(SELECTION, REQUEST, TIMEOUT))
        .isInstanceOf(IllegalStateException.class);
    assertThat(client.generate(SELECTION, REQUEST, TIMEOUT).candidatePresent()).isTrue();

    assertThat(creates).hasValue(2);
    assertThat(sdkClient.generates).hasValue(1);
  }

  @Test
  void mapsSdkCallTimeoutToVendorNeutralTimeout() {
    GenAiIOException sdkTimeout =
        new GenAiIOException(
            "Failed to execute HTTP request.", new InterruptedIOException("timeout"));
    GoogleGenAiLlmClient client =
        new GoogleGenAiLlmClient(selection -> new FailingGoogleSdkClient(sdkTimeout));

    assertThatThrownBy(() -> client.generate(SELECTION, REQUEST, TIMEOUT))
        .isInstanceOf(LlmTimeoutException.class)
        .hasCause(sdkTimeout);
  }

  @Test
  void mapsSdkSocketTimeoutToVendorNeutralTimeout() {
    GenAiIOException sdkTimeout =
        new GenAiIOException(
            "Failed to execute HTTP request.", new SocketTimeoutException("Read timed out"));
    GoogleGenAiLlmClient client =
        new GoogleGenAiLlmClient(selection -> new FailingGoogleSdkClient(sdkTimeout));

    assertThatThrownBy(() -> client.generate(SELECTION, REQUEST, TIMEOUT))
        .isInstanceOf(LlmTimeoutException.class)
        .hasCause(sdkTimeout);
  }

  private static final class FakeGoogleSdkClient implements GoogleSdkClient {

    private final AtomicInteger generates = new AtomicInteger();
    private final AtomicInteger closes = new AtomicInteger();

    @Override
    public LlmResponse generate(ModelSelection selection, LlmRequest request, Duration timeout) {
      generates.incrementAndGet();
      return new LlmResponse(true, "STOP", "{}", "", LlmResponse.TokenUsage.EMPTY);
    }

    @Override
    public void close() {
      closes.incrementAndGet();
    }
  }

  private record FailingGoogleSdkClient(RuntimeException error) implements GoogleSdkClient {

    @Override
    public LlmResponse generate(ModelSelection selection, LlmRequest request, Duration timeout) {
      throw error;
    }

    @Override
    public void close() {}
  }
}
