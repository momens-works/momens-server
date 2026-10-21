package works.momens.server.source.connection.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import works.momens.server.source.connection.oauth.FigmaTokenRefreshClient.RefreshFailedException;
import works.momens.server.source.connection.oauth.ProviderDefinition.ClientAuthStyle;
import works.momens.server.source.connection.oauth.ProviderDefinition.IdentityMethod;
import works.momens.server.source.connection.oauth.ProviderDefinition.TokenBodyFormat;

@DisplayName("Figma token 갱신 HTTP 경계")
class FigmaTokenRefreshClientTest {

  private static final OAuthProvider PROVIDER =
      new OAuthProvider(
          new ProviderDefinition(
              OAuthProviderRegistry.FIGMA,
              "https://www.figma.com/oauth",
              "https://api.figma.com/v1/oauth/token",
              List.of(),
              Map.of(),
              TokenBodyFormat.FORM,
              ClientAuthStyle.BASIC_HEADER,
              null,
              IdentityMethod.NONE,
              ProviderIdentities::figma),
          "client-id",
          "private-secret",
          "https://api.example/callback");

  private final AtomicInteger status = new AtomicInteger(200);
  private final AtomicReference<String> responseBody = new AtomicReference<>();
  private final AtomicReference<String> requestBody = new AtomicReference<>();
  private final AtomicReference<String> authorization = new AtomicReference<>();
  private final AtomicReference<String> contentType = new AtomicReference<>();
  private HttpServer server;

  @BeforeEach
  void setUp() throws IOException {
    responseBody.set(
        "{\"access_token\":\"new-access\",\"token_type\":\"bearer\",\"expires_in\":7776000}");
    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext(
        "/v1/oauth/refresh",
        exchange -> {
          requestBody.set(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
          contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
          byte[] payload = responseBody.get().getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(status.get(), payload.length);
          exchange.getResponseBody().write(payload);
          exchange.close();
        });
    server.start();
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
  }

  private FigmaTokenRefreshClient client() {
    var factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Duration.ofSeconds(1));
    factory.setReadTimeout(Duration.ofSeconds(5));
    return new FigmaTokenRefreshClient(
        RestClient.builder()
            .baseUrl("http://localhost:" + server.getAddress().getPort())
            .requestFactory(factory)
            .build());
  }

  @Test
  @DisplayName("Basic 인증과 form body로 갱신을 요청하고 응답의 만료 시각을 계산한다")
  void refreshes() {
    var refreshed = client().refresh(PROVIDER, "private-refresh");

    assertThat(refreshed.accessToken()).isEqualTo("new-access");
    assertThat(refreshed.tokenType()).isEqualTo("bearer");
    assertThat(refreshed.expiresAt())
        .isCloseTo(Instant.now().plus(90, ChronoUnit.DAYS), within(1, ChronoUnit.MINUTES));
    assertThat(requestBody.get()).isEqualTo("refresh_token=private-refresh");
    assertThat(contentType.get()).startsWith("application/x-www-form-urlencoded");
    assertThat(authorization.get())
        .isEqualTo(
            "Basic "
                + Base64.getEncoder()
                    .encodeToString("client-id:private-secret".getBytes(StandardCharsets.UTF_8)));
  }

  @ParameterizedTest
  @ValueSource(ints = {400, 401, 403})
  @DisplayName("Figma가 갱신을 거부하면 재인증이 필요한 실패로 분류한다")
  void classifiesRejectionAsReauthRequired(int rejected) {
    status.set(rejected);
    responseBody.set("{\"error\":\"private-provider-detail\"}");

    assertThatThrownBy(() -> client().refresh(PROVIDER, "private-refresh"))
        .isInstanceOfSatisfying(
            RefreshFailedException.class,
            e -> {
              assertThat(e.reauthRequired()).isTrue();
              assertThat(e.getMessage()).isEqualTo("status=" + rejected);
            });
  }

  @ParameterizedTest
  @ValueSource(ints = {429, 500, 503})
  @DisplayName("rate limit과 서버 오류는 일시적 실패로 분류한다")
  void classifiesServerErrorsAsTransient(int failed) {
    status.set(failed);

    assertThatThrownBy(() -> client().refresh(PROVIDER, "private-refresh"))
        .isInstanceOfSatisfying(
            RefreshFailedException.class, e -> assertThat(e.reauthRequired()).isFalse());
  }

  @ParameterizedTest
  @ValueSource(strings = {"{}", "{\"access_token\":\" \"}", "{\"access_token\":7}", "not-json"})
  @DisplayName("access token이 없는 성공 응답은 token을 노출하지 않고 일시적 실패로 분류한다")
  void classifiesMalformedResponseAsTransient(String body) {
    responseBody.set(body);

    assertThatThrownBy(() -> client().refresh(PROVIDER, "private-refresh"))
        .isInstanceOfSatisfying(
            RefreshFailedException.class,
            e -> {
              assertThat(e.reauthRequired()).isFalse();
              assertThat(e.getMessage()).doesNotContain("private-refresh", "not-json");
            });
  }

  @Test
  @DisplayName("연결 실패는 일시적 실패로 분류한다")
  void classifiesConnectionFailureAsTransient() {
    var unreachable = client();
    server.stop(0);

    assertThatThrownBy(() -> unreachable.refresh(PROVIDER, "private-refresh"))
        .isInstanceOfSatisfying(
            RefreshFailedException.class, e -> assertThat(e.reauthRequired()).isFalse());
  }
}
