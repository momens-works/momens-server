package works.momens.server.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.OAuth2TokenFormat;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;
import works.momens.server.common.test.AbstractPostgresIntegrationTest;
import works.momens.server.mcp.grant.McpScope;
import works.momens.server.workspace.membership.WorkspaceMembershipReader;

@SpringBootTest(properties = "momens.mcp.resource-uri=https://api.momens.works/api/mcp")
@AutoConfigureMockMvc
@DisplayName("MCP OAuth discovery·client 등록 통합 테스트")
class McpAuthorizationServerIntegrationTest extends AbstractPostgresIntegrationTest {

  private static final Instant NOW = Instant.parse("2026-09-24T00:00:00Z");
  private static final String REGISTER = "/api/oauth2/register";
  private static final String[] ALL_SCOPES =
      Arrays.stream(McpScope.values()).map(McpScope::value).toArray(String[]::new);

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private RegisteredClientRepository registeredClientRepository;

  @MockitoBean private WorkspaceMembershipReader workspaceMembershipReader;

  @Test
  @DisplayName("public PKCE client를 secret 없이 등록하고 reference token 설정으로 저장한다")
  void registersPublicClient() throws Exception {
    String body =
        mockMvc
            .perform(
                post(REGISTER)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json(
                            Map.of(
                                "client_name",
                                "Claude Code",
                                "redirect_uris",
                                List.of("http://localhost:33418/callback"),
                                "token_endpoint_auth_method",
                                "none",
                                "grant_types",
                                List.of("authorization_code", "refresh_token"),
                                "response_types",
                                List.of("code"),
                                "scope",
                                "unknown:scope"))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.client_name").value("Claude Code"))
            .andExpect(jsonPath("$.token_endpoint_auth_method").value("none"))
            .andExpect(jsonPath("$.redirect_uris[0]").value("http://localhost:33418/callback"))
            .andExpect(
                jsonPath(
                    "$.grant_types", containsInAnyOrder("authorization_code", "refresh_token")))
            .andExpect(jsonPath("$.response_types[0]").value("code"))
            .andExpect(jsonPath("$.client_secret").doesNotExist())
            .andExpect(jsonPath("$.client_secret_expires_at").doesNotExist())
            .andReturn()
            .getResponse()
            .getContentAsString();

    String clientId = objectMapper.readTree(body).get("client_id").stringValue();
    assertThat(objectMapper.readTree(body).get("scope").stringValue().split(" "))
        .containsExactlyInAnyOrder(ALL_SCOPES);
    RegisteredClient client = registeredClientRepository.findByClientId(clientId);
    assertThat(client).isNotNull();
    assertThat(client.getClientSecret()).isNull();
    assertThat(client.getClientIdIssuedAt()).isEqualTo(NOW);
    assertThat(client.getClientAuthenticationMethods())
        .containsExactly(ClientAuthenticationMethod.NONE);
    assertThat(client.getAuthorizationGrantTypes())
        .containsExactlyInAnyOrder(
            AuthorizationGrantType.AUTHORIZATION_CODE, AuthorizationGrantType.REFRESH_TOKEN);
    assertThat(client.getScopes()).containsExactlyInAnyOrder(ALL_SCOPES);
    assertThat(client.getClientSettings().isRequireProofKey()).isTrue();
    assertThat(client.getClientSettings().isRequireAuthorizationConsent()).isTrue();
    assertThat(client.getTokenSettings().getAccessTokenFormat())
        .isEqualTo(OAuth2TokenFormat.REFERENCE);
    assertThat(client.getTokenSettings().getAuthorizationCodeTimeToLive())
        .isEqualTo(Duration.ofMinutes(5));
    assertThat(client.getTokenSettings().getAccessTokenTimeToLive()).isEqualTo(Duration.ofHours(1));
    assertThat(client.getTokenSettings().getRefreshTokenTimeToLive())
        .isEqualTo(Duration.ofDays(90));
    assertThat(client.getTokenSettings().isReuseRefreshTokens()).isFalse();
  }

  @Test
  @DisplayName("token_endpoint_auth_method를 생략하면 public client로 등록한다")
  void registersPublicClientWhenAuthMethodIsOmitted() throws Exception {
    mockMvc
        .perform(post(REGISTER).contentType(MediaType.APPLICATION_JSON).content(registration()))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.token_endpoint_auth_method").value("none"))
        .andExpect(jsonPath("$.client_secret").doesNotExist());
  }

  @Test
  @DisplayName("Authorization 헤더를 initial access token으로 해석하지 않는다")
  void ignoresBearerTokenOnOpenRegistration() throws Exception {
    mockMvc
        .perform(
            post(REGISTER)
                .header("Authorization", "Bearer not-an-initial-access-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(registration()))
        .andExpect(status().isCreated());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "http://localhost:3000/callback",
        "http://127.0.0.1:53682/callback",
        "http://[::1]:8080/callback",
        "https://client.example.com/oauth/callback"
      })
  @DisplayName("HTTPS와 http loopback redirect URI를 허용한다")
  void acceptsAllowedRedirectUris(String redirectUri) throws Exception {
    mockMvc
        .perform(
            post(REGISTER)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json(Map.of("client_name", "client", "redirect_uris", List.of(redirectUri)))))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.redirect_uris[0]").value(redirectUri));
  }

  @ParameterizedTest
  @MethodSource("rejectedRedirectUris")
  @DisplayName("허용하지 않는 redirect URI는 invalid_redirect_uri로 거부한다")
  void rejectsDisallowedRedirectUris(List<String> redirectUris) throws Exception {
    mockMvc
        .perform(
            post(REGISTER)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("client_name", "client", "redirect_uris", redirectUris))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("invalid_redirect_uri"));
  }

  static Stream<Arguments> rejectedRedirectUris() {
    return Stream.of(
        Arguments.of(List.of("http://client.example.com/callback")),
        Arguments.of(List.of("http://localhost.example.com/callback")),
        Arguments.of(List.of("https://client.example.com/callback#fragment")),
        Arguments.of(List.of("https://user@client.example.com/callback")),
        Arguments.of(List.of("com.example.app:/callback")),
        Arguments.of(List.of("/callback")),
        Arguments.of(List.of("https://client.example.com/callback?state=a,b")),
        Arguments.of(List.of("https://client.example.com/" + "a".repeat(1100))),
        Arguments.of(
            Stream.iterate(0, i -> i + 1)
                .limit(10)
                .map(i -> "https://client.example.com/" + i + "/" + "a".repeat(100))
                .toList()),
        Arguments.of(
            Stream.iterate(0, i -> i + 1)
                .limit(11)
                .map(i -> "https://client.example.com/callback/" + i)
                .toList()));
  }

  @Test
  @DisplayName("redirect_uris가 없으면 invalid_redirect_uri, 빈 배열이면 invalid_request로 거부한다")
  void rejectsMissingOrEmptyRedirectUris() throws Exception {
    mockMvc
        .perform(
            post(REGISTER)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("client_name", "client"))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("invalid_redirect_uri"));
    mockMvc
        .perform(
            post(REGISTER)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("client_name", "client", "redirect_uris", List.of()))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("invalid_request"))
        .andExpect(jsonPath("$.error_description").value("Invalid client registration request"));
  }

  @Test
  @DisplayName("invalid_request 오류는 내부 예외 메시지 대신 일반 설명을 반환한다")
  void hidesInternalMessageInInvalidRequest() throws Exception {
    mockMvc
        .perform(
            post(REGISTER).contentType(MediaType.APPLICATION_JSON).content("{\"client_name\":"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("invalid_request"))
        .andExpect(jsonPath("$.error_description").value("Invalid client registration request"));
  }

  @ParameterizedTest
  @MethodSource("rejectedClientMetadata")
  @DisplayName("public PKCE client가 아닌 metadata는 invalid_client_metadata로 거부한다")
  void rejectsInvalidClientMetadata(Map<String, Object> metadata) throws Exception {
    mockMvc
        .perform(post(REGISTER).contentType(MediaType.APPLICATION_JSON).content(json(metadata)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("invalid_client_metadata"))
        .andExpect(jsonPath("$.error_description").isNotEmpty());
  }

  static Stream<Arguments> rejectedClientMetadata() {
    List<String> redirectUris = List.of("http://localhost:3000/callback");
    return Stream.of(
        Arguments.of(Map.of("redirect_uris", redirectUris)),
        Arguments.of(Map.of("client_name", " ", "redirect_uris", redirectUris)),
        Arguments.of(Map.of("client_name", "a".repeat(121), "redirect_uris", redirectUris)),
        Arguments.of(
            Map.of(
                "client_name",
                "client",
                "redirect_uris",
                redirectUris,
                "token_endpoint_auth_method",
                "client_secret_basic")),
        Arguments.of(
            Map.of(
                "client_name",
                "client",
                "redirect_uris",
                redirectUris,
                "grant_types",
                List.of("client_credentials"))),
        Arguments.of(
            Map.of(
                "client_name",
                "client",
                "redirect_uris",
                redirectUris,
                "response_types",
                List.of("token"))));
  }

  @Test
  @DisplayName("/api issuer 기준 authorization server metadata를 제공한다")
  void exposesAuthorizationServerMetadata() throws Exception {
    mockMvc
        .perform(get("/.well-known/oauth-authorization-server/api"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.issuer").value("https://api.momens.works/api"))
        .andExpect(
            jsonPath("$.authorization_endpoint")
                .value("https://api.momens.works/api/oauth2/authorize"))
        .andExpect(jsonPath("$.token_endpoint").value("https://api.momens.works/api/oauth2/token"))
        .andExpect(
            jsonPath("$.registration_endpoint")
                .value("https://api.momens.works/api/oauth2/register"))
        .andExpect(
            jsonPath("$.revocation_endpoint").value("https://api.momens.works/api/oauth2/revoke"))
        .andExpect(jsonPath("$.response_types_supported", containsInAnyOrder("code")))
        .andExpect(
            jsonPath(
                "$.grant_types_supported",
                containsInAnyOrder("authorization_code", "refresh_token")))
        .andExpect(jsonPath("$.code_challenge_methods_supported", containsInAnyOrder("S256")))
        .andExpect(jsonPath("$.token_endpoint_auth_methods_supported", containsInAnyOrder("none")))
        .andExpect(jsonPath("$.scopes_supported", containsInAnyOrder(ALL_SCOPES)))
        .andExpect(jsonPath("$.client_id_metadata_document_supported").doesNotExist());
  }

  @Test
  @DisplayName("root authorization server metadata 경로는 MCP issuer로 응답하지 않는다")
  void doesNotServeRootAuthorizationServerMetadata() throws Exception {
    mockMvc
        .perform(get("/.well-known/oauth-authorization-server"))
        .andExpect(status().is(not(200)));
  }

  private String registration() {
    return json(
        Map.of(
            "client_name", "client", "redirect_uris", List.of("http://localhost:3000/callback")));
  }

  private String json(Map<String, Object> value) {
    return objectMapper.writeValueAsString(value);
  }

  @TestConfiguration
  static class McpTimeConfig {

    @Bean
    Clock clock() {
      return Clock.fixed(NOW, ZoneOffset.UTC);
    }
  }
}
