package works.momens.server.auth.internal.google;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import works.momens.server.auth.AuthErrorCode;
import works.momens.server.auth.internal.config.AuthProperties;
import works.momens.server.common.api.BusinessException;

/** 웹 Authorization Code 교환·userinfo 조회를 로컬 HttpServer 목으로 검증합니다. */
class GoogleOAuthClientTest {

  private static final String TOKEN_BODY = "{\"access_token\":\"google-access-token\"}";
  private static final String VERIFIED_USERINFO =
      "{\"sub\":\"google-sub\",\"email\":\"user@example.com\",\"email_verified\":true,"
          + "\"name\":\"홍길동\",\"picture\":\"https://cdn.momens.works/avatar.png\"}";
  private static final String UNVERIFIED_USERINFO =
      "{\"sub\":\"google-sub\",\"email\":\"user@example.com\",\"email_verified\":false,"
          + "\"name\":\"홍길동\"}";
  private static final String MISSING_VERIFIED_USERINFO =
      "{\"sub\":\"google-sub\",\"email\":\"user@example.com\",\"name\":\"홍길동\"}";
  private static final String MISSING_SUB_USERINFO =
      "{\"email\":\"user@example.com\",\"email_verified\":true,\"name\":\"홍길동\"}";

  @Test
  void exchangeCodeReturnsGoogleAccessToken() throws Exception {
    try (GoogleServer server = GoogleServer.start(TOKEN_BODY, VERIFIED_USERINFO)) {
      GoogleOAuthClient client = client(server);

      String accessToken = client.exchangeCode("auth-code", "code-verifier");

      assertThat(accessToken).isEqualTo("google-access-token");
    }
  }

  @Test
  void fetchUserInfoReturnsProfileForVerifiedEmail() throws Exception {
    try (GoogleServer server = GoogleServer.start(TOKEN_BODY, VERIFIED_USERINFO)) {
      GoogleOAuthClient client = client(server);

      GoogleUserInfo user = client.fetchUserInfo("google-access-token");

      assertThat(user.sub()).isEqualTo("google-sub");
      assertThat(user.email()).isEqualTo("user@example.com");
      assertThat(user.name()).isEqualTo("홍길동");
      assertThat(user.picture()).isEqualTo("https://cdn.momens.works/avatar.png");
    }
  }

  @Test
  void fetchUserInfoRejectsUnverifiedEmail() throws Exception {
    try (GoogleServer server = GoogleServer.start(TOKEN_BODY, UNVERIFIED_USERINFO)) {
      GoogleOAuthClient client = client(server);

      assertThatThrownBy(() -> client.fetchUserInfo("google-access-token"))
          .isInstanceOfSatisfying(
              BusinessException.class,
              e ->
                  assertThat(e.getErrorCode())
                      .isEqualTo(AuthErrorCode.AUTH_GOOGLE_EMAIL_NOT_VERIFIED));
    }
  }

  @Test
  void fetchUserInfoTreatsMissingSubAsExchangeFailure() throws Exception {
    try (GoogleServer server = GoogleServer.start(TOKEN_BODY, MISSING_SUB_USERINFO)) {
      GoogleOAuthClient client = client(server);

      assertThatThrownBy(() -> client.fetchUserInfo("google-access-token"))
          .isInstanceOfSatisfying(
              BusinessException.class,
              e ->
                  assertThat(e.getErrorCode()).isEqualTo(AuthErrorCode.AUTH_OAUTH_EXCHANGE_FAILED));
    }
  }

  @Test
  void fetchUserInfoTreatsMissingEmailVerifiedAsExchangeFailure() throws Exception {
    try (GoogleServer server = GoogleServer.start(TOKEN_BODY, MISSING_VERIFIED_USERINFO)) {
      GoogleOAuthClient client = client(server);

      assertThatThrownBy(() -> client.fetchUserInfo("google-access-token"))
          .isInstanceOfSatisfying(
              BusinessException.class,
              e ->
                  assertThat(e.getErrorCode()).isEqualTo(AuthErrorCode.AUTH_OAUTH_EXCHANGE_FAILED));
    }
  }

  @Test
  void exchangeCodeFailsWhenGoogleReturnsError() throws Exception {
    try (GoogleServer server = GoogleServer.startTokenError()) {
      GoogleOAuthClient client = client(server);

      assertThatThrownBy(() -> client.exchangeCode("auth-code", "code-verifier"))
          .isInstanceOfSatisfying(
              BusinessException.class,
              e ->
                  assertThat(e.getErrorCode()).isEqualTo(AuthErrorCode.AUTH_OAUTH_EXCHANGE_FAILED));
    }
  }

  @Test
  void authorizationUrlCarriesStateAndPkceChallenge() throws Exception {
    try (GoogleServer server = GoogleServer.start(TOKEN_BODY, VERIFIED_USERINFO)) {
      GoogleOAuthClient client = client(server);

      String url = client.authorizationUrl("state-123", "challenge-abc");

      assertThat(url).startsWith("https://accounts.google.com/o/oauth2/v2/auth");
      assertThat(url).contains("client_id=web-client-id");
      assertThat(url).contains("response_type=code");
      assertThat(url).contains("state=state-123");
      assertThat(url).contains("code_challenge=challenge-abc");
      assertThat(url).contains("code_challenge_method=S256");
    }
  }

  private static GoogleOAuthClient client(GoogleServer server) {
    return new GoogleOAuthClient(RestClient.create(), properties(server));
  }

  private static AuthProperties properties(GoogleServer server) {
    return new AuthProperties(
        "unit-test-momens-auth-jwt-secret-0123456789abcdef",
        Duration.ofMinutes(15),
        Duration.ofDays(14),
        new AuthProperties.Google(
            List.of("test-google-client-id.apps.googleusercontent.com"),
            "https://www.googleapis.com/oauth2/v3/certs"),
        new AuthProperties.Web(
            new AuthProperties.Web.GoogleOauth(
                "web-client-id",
                "web-client-secret",
                "http://localhost:8080/api/auth/google/callback",
                "https://accounts.google.com/o/oauth2/v2/auth",
                server.tokenUri(),
                server.userinfoUri(),
                List.of("openid", "email", "profile")),
            null,
            null));
  }

  private record GoogleServer(HttpServer server, String tokenUri, String userinfoUri)
      implements AutoCloseable {

    static GoogleServer start(String tokenBody, String userinfoBody) throws IOException {
      HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext("/token", jsonHandler(200, tokenBody));
      server.createContext("/userinfo", jsonHandler(200, userinfoBody));
      server.start();
      String base = "http://127.0.0.1:" + server.getAddress().getPort();
      return new GoogleServer(server, base + "/token", base + "/userinfo");
    }

    static GoogleServer startTokenError() throws IOException {
      HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext("/token", jsonHandler(400, "{\"error\":\"invalid_grant\"}"));
      server.start();
      String base = "http://127.0.0.1:" + server.getAddress().getPort();
      return new GoogleServer(server, base + "/token", base + "/userinfo");
    }

    private static HttpHandler jsonHandler(int status, String body) {
      return exchange -> {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
      };
    }

    @Override
    public void close() {
      server.stop(0);
    }
  }
}
