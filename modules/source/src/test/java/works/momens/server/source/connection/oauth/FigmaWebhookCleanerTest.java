package works.momens.server.source.connection.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import works.momens.server.source.connection.SourceCredential;
import works.momens.server.source.connection.SourceCredentialRepository;

@ExtendWith(OutputCaptureExtension.class)
@DisplayName("Figma webhook 정리 테스트")
class FigmaWebhookCleanerTest {

  private final UUID connectionId = UUID.randomUUID();
  private final SourceCredentialRepository credentials = mock(SourceCredentialRepository.class);
  private final TokenEncryptor encryptor =
      new TokenEncryptor(
          Base64.getEncoder()
              .encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8)));
  private final AtomicInteger calls = new AtomicInteger();
  private final AtomicInteger responseStatus = new AtomicInteger(200);
  private final AtomicReference<String> authorization = new AtomicReference<>();
  private final AtomicReference<String> request = new AtomicReference<>();
  private HttpServer server;
  private FigmaWebhookCleaner cleaner;
  private CountDownLatch respond;

  @BeforeEach
  void setUp() throws Exception {
    respond = new CountDownLatch(0);
    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext(
        "/v2/webhooks",
        exchange -> {
          calls.incrementAndGet();
          authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
          request.set(exchange.getRequestMethod() + " " + exchange.getRequestURI().getRawPath());
          try {
            respond.await(2, TimeUnit.SECONDS);
            byte[] body = "provider-private-response".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(responseStatus.get(), body.length);
            exchange.getResponseBody().write(body);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          } finally {
            exchange.close();
          }
        });
    server.start();
    var factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Duration.ofSeconds(1));
    factory.setReadTimeout(Duration.ofMillis(150));
    cleaner =
        new FigmaWebhookCleaner(
            credentials,
            encryptor,
            new FigmaWebhookClient(
                RestClient.builder()
                    .baseUrl("http://localhost:" + server.getAddress().getPort())
                    .requestFactory(factory)
                    .build()));
    stored(encryptor.encrypt("private-access-token"));
  }

  @AfterEach
  void tearDown() {
    respond.countDown();
    server.stop(0);
  }

  @Test
  @DisplayName("복호화한 Bearer 토큰으로 지정한 webhook을 삭제한다")
  void deletesExactWebhookWithDecryptedBearerToken(CapturedOutput output) {
    cleaner.delete(connectionId, "wh-123");
    assertThat(calls.get()).isEqualTo(1);
    assertThat(request.get()).isEqualTo("DELETE /v2/webhooks/wh-123");
    assertThat(authorization.get()).isEqualTo("Bearer private-access-token");
    assertThat(output).doesNotContain("private-access-token", "provider-private-response");
  }

  @ParameterizedTest
  @ValueSource(ints = {401, 404, 429, 500})
  @DisplayName("provider 실패는 전파하지 않고 응답 본문과 토큰을 로그에 남기지 않는다")
  void providerFailureDoesNotEscapeOrLeakResponse(int status, CapturedOutput output) {
    responseStatus.set(status);
    cleaner.delete(connectionId, "wh-123");
    assertThat(calls.get()).isEqualTo(1);
    assertThat(output)
        .contains("status=" + status, connectionId.toString())
        .doesNotContain("private-access-token", "provider-private-response");
  }

  @Test
  @DisplayName("provider 타임아웃은 전파하지 않고 안전한 경고를 남긴다")
  void timeoutDoesNotEscape(CapturedOutput output) {
    respond = new CountDownLatch(1);
    cleaner.delete(connectionId, "wh-123");
    assertThat(output)
        .contains("failureType=ResourceAccessException")
        .doesNotContain("private-access-token");
  }

  @Test
  @DisplayName("저장된 자격 증명이 없으면 provider를 호출하지 않는다")
  void missingCredentialDoesNotCallProvider(CapturedOutput output) {
    when(credentials.findById(connectionId)).thenReturn(Optional.empty());
    cleaner.delete(connectionId, "wh-123");
    assertThat(calls.get()).isZero();
    assertThat(output).contains("reason=missing_token");
  }

  @Test
  @DisplayName("암호문이 손상되면 provider를 호출하지 않고 경고를 남긴다")
  void malformedCredentialDoesNotCallProvider(CapturedOutput output) {
    stored(new byte[] {1, 2, 3});
    cleaner.delete(connectionId, "wh-123");
    assertThat(calls.get()).isZero();
    assertThat(output).contains("failureType=IllegalArgumentException");
  }

  @Test
  @DisplayName("복호화한 토큰이 공백이면 provider를 호출하지 않는다")
  void blankTokenDoesNotCallProvider() {
    stored(encryptor.encrypt("  "));
    cleaner.delete(connectionId, "wh-123");
    assertThat(calls.get()).isZero();
  }

  @Test
  @DisplayName("자격 증명 조회 실패는 전파하지 않고 내부 오류 내용을 로그에 남기지 않는다")
  void credentialReadFailureDoesNotEscapeOrLeakDetails(CapturedOutput output) {
    when(credentials.findById(connectionId))
        .thenThrow(new DataAccessResourceFailureException("private-database-details"));
    cleaner.delete(connectionId, "wh-123");
    assertThat(calls.get()).isZero();
    assertThat(output)
        .contains("failureType=DataAccessResourceFailureException")
        .doesNotContain("private-database-details");
  }

  private void stored(byte[] ciphertext) {
    when(credentials.findById(connectionId))
        .thenReturn(
            Optional.of(
                SourceCredential.builder()
                    .connectionId(connectionId)
                    .accessTokenEnc(ciphertext)
                    .build()));
  }
}
