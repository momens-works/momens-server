package works.momens.server.source.connection.oauth;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import works.momens.server.source.connection.oauth.ProviderDefinition.ClientAuthStyle;
import works.momens.server.source.connection.oauth.ProviderDefinition.IdentityMethod;
import works.momens.server.source.connection.oauth.ProviderDefinition.TokenBodyFormat;

class ProviderOAuthClientTest {

  private HttpServer server;
  private final Map<String, String> bodies = new ConcurrentHashMap<>();
  private final Map<String, String> authorizations = new ConcurrentHashMap<>();
  private final Map<String, String> contentTypes = new ConcurrentHashMap<>();
  private final Map<String, String> methods = new ConcurrentHashMap<>();
  private final ProviderOAuthClient exchanger =
      new ProviderOAuthClient(RestClient.builder().build());

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext(
        "/token",
        exchange -> record(exchange, "{\"access_token\":\"tok\",\"workspace_id\":\"ws-1\"}"));
    server.createContext(
        "/identity",
        exchange -> record(exchange, "{\"login\":\"jsshin\",\"ok\":true,\"team_id\":\"T-1\"}"));
    server.start();
  }

  @AfterEach
  void stopServer() {
    server.stop(0);
  }

  private void record(HttpExchange exchange, String responseBody) throws IOException {
    String path = exchange.getRequestURI().getPath();
    methods.put(path, exchange.getRequestMethod());
    bodies.put(path, new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
    authorizations.put(
        path, String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
    contentTypes.put(path, String.valueOf(exchange.getRequestHeaders().getFirst("Content-Type")));
    byte[] payload = responseBody.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, payload.length);
    exchange.getResponseBody().write(payload);
    exchange.close();
  }

  private String url(String path) {
    return "http://localhost:" + server.getAddress().getPort() + path;
  }

  private OAuthProvider provider(
      TokenBodyFormat bodyFormat, ClientAuthStyle authStyle, IdentityMethod identityMethod) {
    ProviderDefinition definition =
        new ProviderDefinition(
            "TEST",
            url("/authorize"),
            url("/token"),
            List.of(),
            Map.of(),
            bodyFormat,
            authStyle,
            url("/identity"),
            identityMethod,
            ProviderIdentities::github);
    return new OAuthProvider(definition, "cid", "secret", "https://api.momens.works/callback");
  }

  @Test
  @DisplayName("자격 증명을 본문으로 받는 provider에는 form 본문으로 전송한다")
  void sendsClientCredentialsInTheFormBodyWhenTheProviderExpectsThem() {
    exchanger.exchange(
        provider(TokenBodyFormat.FORM, ClientAuthStyle.REQUEST_BODY, IdentityMethod.NONE),
        "code-1");

    assertThat(contentTypes.get("/token")).startsWith("application/x-www-form-urlencoded");
    assertThat(bodies.get("/token"))
        .contains("grant_type=authorization_code")
        .contains("code=code-1")
        .contains("client_id=cid")
        .contains("client_secret=secret");
    assertThat(authorizations.get("/token")).isEqualTo("null");
  }

  @Test
  @DisplayName("자격 증명을 헤더로 받는 provider에는 Authorization 헤더로 전송한다")
  void sendsClientCredentialsInTheAuthorizationHeaderWhenTheProviderExpectsThem() {
    exchanger.exchange(
        provider(TokenBodyFormat.FORM, ClientAuthStyle.BASIC_HEADER, IdentityMethod.NONE),
        "code-2");

    assertThat(authorizations.get("/token")).isEqualTo("Basic Y2lkOnNlY3JldA==");
    assertThat(bodies.get("/token")).doesNotContain("client_secret");
  }

  @Test
  @DisplayName("JSON 본문을 받는 provider에는 JSON 형식으로 전송한다")
  void sendsJsonTokenBodyWhenTheProviderExpectsJson() {
    exchanger.exchange(
        provider(TokenBodyFormat.JSON, ClientAuthStyle.BASIC_HEADER, IdentityMethod.NONE),
        "code-3");

    assertThat(contentTypes.get("/token")).startsWith("application/json");
    assertThat(bodies.get("/token"))
        .isEqualTo(
            "{\"grant_type\":\"authorization_code\",\"code\":\"code-3\","
                + "\"redirect_uri\":\"https://api.momens.works/callback\"}");
  }

  @Test
  @DisplayName("사용자 정보 URL이 있는 provider는 발급받은 토큰으로 사용자 정보를 조회한다")
  void readsIdentityWithTheAccessTokenWhenTheProviderHasAnIdentityEndpoint() {
    OAuthProvider provider =
        provider(TokenBodyFormat.FORM, ClientAuthStyle.REQUEST_BODY, IdentityMethod.GET);

    Map<String, Object> identity =
        exchanger.fetchIdentity(provider, exchanger.exchange(provider, "code-4"));

    assertThat(methods.get("/identity")).isEqualTo("GET");
    assertThat(authorizations.get("/identity")).isEqualTo("Bearer tok");
    assertThat(identity).containsEntry("login", "jsshin");
  }

  @Test
  @DisplayName("사용자 정보를 POST로 조회하는 provider에는 POST로 요청한다")
  void readsIdentityWithPostWhenTheProviderExpectsPost() {
    OAuthProvider provider =
        provider(TokenBodyFormat.FORM, ClientAuthStyle.BASIC_HEADER, IdentityMethod.POST);

    exchanger.fetchIdentity(provider, exchanger.exchange(provider, "code-5"));

    assertThat(methods.get("/identity")).isEqualTo("POST");
  }

  @Test
  @DisplayName("토큰 응답에 계정 정보가 포함되어 있으면 사용자 정보를 별도로 조회하지 않는다")
  void doesNotCallAnyEndpointWhenIdentityComesFromTheTokenResponse() {
    OAuthProvider provider =
        provider(TokenBodyFormat.JSON, ClientAuthStyle.BASIC_HEADER, IdentityMethod.NONE);

    Map<String, Object> identity =
        exchanger.fetchIdentity(provider, exchanger.exchange(provider, "code-6"));

    assertThat(identity).isEmpty();
    assertThat(methods).doesNotContainKey("/identity");
  }
}
