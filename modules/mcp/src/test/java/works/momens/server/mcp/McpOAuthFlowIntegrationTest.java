package works.momens.server.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import works.momens.server.common.persistence.JpaAuditingConfig;
import works.momens.server.common.test.AbstractPostgresIntegrationTest;
import works.momens.server.mcp.grant.McpGrantWriter;
import works.momens.server.workspace.core.WorkspaceReader;
import works.momens.server.workspace.membership.WorkspaceMembershipReader;
import works.momens.server.workspace.membership.WorkspaceRole;

@SpringBootTest(
    properties = {
      "momens.mcp.resource-uri=https://api.momens.works/api/mcp",
      "momens.mcp.oauth.consent-uri=https://app.example.com/oauth/authorize",
      "spring.jackson.property-naming-strategy=SNAKE_CASE"
    })
@Import(JpaAuditingConfig.class)
@AutoConfigureMockMvc
class McpOAuthFlowIntegrationTest extends AbstractPostgresIntegrationTest {
  private static final String RESOURCE = "https://api.momens.works/api/mcp";
  private static final String REDIRECT = "http://localhost:3000/callback";
  private static final String VERIFIER = "a".repeat(43);
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired McpGrantWriter grants;
  @MockitoBean WorkspaceMembershipReader memberships;
  @MockitoBean WorkspaceReader workspaces;
  UUID userId;
  UUID workspaceId;
  String clientId;

  @BeforeEach
  void setup() throws Exception {
    userId = UUID.randomUUID();
    workspaceId = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO users (id, email, name) VALUES (?, ?, 'OAuth test')",
        userId,
        userId + "@example.com");
    jdbc.update(
        "INSERT INTO workspaces (id, name, slug) VALUES (?, 'OAuth test', ?)",
        workspaceId,
        workspaceId.toString());
    when(memberships.roleOf(workspaceId, userId)).thenReturn(Optional.of(WorkspaceRole.MEMBER));
    when(workspaces.listByMemberUserId(userId)).thenReturn(List.of());
    String body =
        mvc.perform(
                post("/api/oauth2/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        mapper.writeValueAsString(
                            Map.of("client_name", "test", "redirect_uris", List.of(REDIRECT)))))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    clientId = mapper.readTree(body).get("client_id").stringValue();
  }

  @Test
  void authorizesRotatesAndRevokesPublicClientTokensWithoutStoringSecrets() throws Exception {
    String code = approve(begin());
    JsonNode pair = exchange(code);
    String access = pair.get("access_token").stringValue();
    String refresh = pair.get("refresh_token").stringValue();
    assertThat(pair.get("token_type").stringValue()).isEqualTo("Bearer");
    String stored =
        jdbc.queryForObject(
            "SELECT row_to_json(a)::text FROM oauth2_authorization a WHERE principal_name = ?",
            String.class,
            userId.toString());
    assertThat(stored).doesNotContain(code, access, refresh);
    JsonNode rotated = refresh(refresh);
    assertThat(rotated.get("refresh_token").stringValue()).isNotEqualTo(refresh);
    mvc.perform(
            post("/api/oauth2/revoke")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("client_id", clientId)
                .param("token", rotated.get("refresh_token").stringValue()))
        .andExpect(status().isOk());
    mvc.perform(refreshRequest(rotated.get("refresh_token").stringValue()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("invalid_grant"));
  }

  @Test
  void refreshReuseRevokesTheCurrentFamilyAndAccessToken() throws Exception {
    JsonNode pair = exchange(approve(begin()));
    String refresh = pair.get("refresh_token").stringValue();
    JsonNode rotated = refresh(refresh);
    mvc.perform(refreshRequest(refresh))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("invalid_grant"));
    mvc.perform(refreshRequest(rotated.get("refresh_token").stringValue()))
        .andExpect(status().isBadRequest());
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM mcp_token_families f JOIN oauth2_authorization a ON a.id = f.authorization_id WHERE a.principal_name = ? AND f.revoked_at IS NOT NULL",
                Integer.class,
                userId.toString()))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT access_token_metadata FROM oauth2_authorization WHERE principal_name = ?",
                String.class,
                userId.toString()))
        .contains("invalidated", "true");
  }

  @Test
  void rejectsCodeReplayAndWrongVerifierResourceRedirectOrClient() throws Exception {
    String code = approve(begin());
    mvc.perform(
            exchangeRequest(code)
                .with(
                    request -> {
                      request.setParameter("code_verifier", "b".repeat(43));
                      return request;
                    }))
        .andExpect(status().isBadRequest());
    mvc.perform(
            exchangeRequest(code)
                .with(
                    request -> {
                      request.setParameter("resource", "https://wrong.example/api/mcp");
                      return request;
                    }))
        .andExpect(status().isBadRequest());
    mvc.perform(
            exchangeRequest(code)
                .with(
                    request -> {
                      request.setParameter("redirect_uri", "http://localhost:9999/callback");
                      return request;
                    }))
        .andExpect(status().isBadRequest());
    exchange(code);
    mvc.perform(exchangeRequest(code))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("invalid_grant"));
  }

  @Test
  void deniesAndExpiresInteractionsWithoutIssuingCode() throws Exception {
    String id = begin();
    mvc.perform(post("/api/oauth/interactions/" + id + "/deny").with(user(userId.toString())))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.redirect_to")
                .value(
                    REDIRECT
                        + "?error=access_denied&error_description=The%20resource%20owner%20denied%20the%20request&state=client-state"));
    mvc.perform(approveRequest(id))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error").value("interaction_decided"));
    String expired = begin();
    jdbc.update(
        "UPDATE oauth2_authorization SET attributes = jsonb_set(attributes::jsonb, '{mcp.interaction.expires_at}', '\"2000-01-01T00:00:00Z\"')::text WHERE id = ?",
        expired);
    mvc.perform(approveRequest(expired))
        .andExpect(status().isGone())
        .andExpect(jsonPath("$.error").value("interaction_expired"));
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM oauth2_authorization WHERE id IN (?, ?) AND authorization_code_value IS NOT NULL",
                Integer.class,
                id,
                expired))
        .isZero();
  }

  @Test
  void grantRevocationUsesTheRealFamilyAdapter() throws Exception {
    JsonNode pair = exchange(approve(begin()));
    UUID grantId =
        jdbc.queryForObject("SELECT id FROM mcp_grants WHERE user_id = ?", UUID.class, userId);
    grants.revoke(grantId, null);
    mvc.perform(refreshRequest(pair.get("refresh_token").stringValue()))
        .andExpect(status().isBadRequest());
  }

  @Test
  void malformedPrincipalPreservesStandardAuthError() throws Exception {
    mvc.perform(get("/api/oauth/interactions/" + UUID.randomUUID()).with(user("not-a-uuid")))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error.code").value("AUTH_INVALID_TOKEN"));
  }

  @Test
  void rejectsUnknownInteractionWithLegacyError() throws Exception {
    mvc.perform(approveRequest(UUID.randomUUID().toString()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("invalid_interaction"));
  }

  @Test
  void rejectsNonMemberConsent() throws Exception {
    String id = begin();
    when(memberships.roleOf(workspaceId, userId)).thenReturn(Optional.empty());
    mvc.perform(approveRequest(id))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error").value("forbidden"));
  }

  @Test
  void validatesAuthorizeBeforeRedirectingToConsent() throws Exception {
    mvc.perform(authorizeRequest(Map.of("redirect_uri", "https://evil.example/callback")))
        .andExpect(status().isBadRequest());
    mvc.perform(authorizeRequest(Map.of("client_id", "unknown")))
        .andExpect(status().isBadRequest());
  }

  @Test
  void rejectsPlainPkceWrongResourceAndUnregisteredLoopbackPort() throws Exception {
    for (Map<String, String> values :
        List.of(
            Map.of("code_challenge_method", "plain"),
            Map.of("resource", "https://wrong.example/api/mcp"),
            Map.of("code_challenge", "short"),
            Map.of("scope", "unknown:scope"),
            Map.of("request_uri", "urn:example:par"))) {
      mvc.perform(authorizeRequest(values))
          .andExpect(status().isFound())
          .andExpect(header().string("Location", containsString("error=")));
    }
    mvc.perform(authorizeRequest(Map.of("redirect_uri", "http://localhost:3001/callback")))
        .andExpect(status().isBadRequest());
  }

  @Test
  void rejectsForeignClientWithoutRevokingTheOwnersFamily() throws Exception {
    JsonNode pair = exchange(approve(begin()));
    String refresh = pair.get("refresh_token").stringValue();
    String other =
        mapper
            .readTree(
                mvc.perform(
                        post("/api/oauth2/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(
                                mapper.writeValueAsString(
                                    Map.of(
                                        "client_name",
                                        "other",
                                        "redirect_uris",
                                        List.of(REDIRECT)))))
                    .andExpect(status().isCreated())
                    .andReturn()
                    .getResponse()
                    .getContentAsString())
            .get("client_id")
            .stringValue();
    mvc.perform(
            refreshRequest(refresh)
                .with(
                    request -> {
                      request.setParameter("client_id", other);
                      return request;
                    }))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("invalid_grant"));
    mvc.perform(post("/api/oauth2/revoke").param("client_id", other).param("token", refresh))
        .andExpect(status().isOk());
    refresh(refresh);
  }

  @Test
  void rejectsScopeEscalationAndRemovedMembership() throws Exception {
    String refresh = exchange(approve(begin())).get("refresh_token").stringValue();
    mvc.perform(refreshRequest(refresh).param("scope", "mcp:tasks:write"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("invalid_scope"));
    when(memberships.roleOf(workspaceId, userId)).thenReturn(Optional.empty());
    mvc.perform(refreshRequest(refresh))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("invalid_grant"));
  }

  @Test
  void concurrentCodeExchangeSucceedsOnlyOnce() throws Exception {
    String code = approve(begin());
    try (var executor = Executors.newFixedThreadPool(2)) {
      var start = new CountDownLatch(1);
      Callable<Integer> exchange =
          () -> {
            start.await();
            return mvc.perform(exchangeRequest(code)).andReturn().getResponse().getStatus();
          };
      var first = executor.submit(exchange);
      var second = executor.submit(exchange);
      start.countDown();
      assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(200, 400);
    }
  }

  @Test
  void concurrentRefreshDetectsReuseAndRevokesTheWinner() throws Exception {
    String refresh = exchange(approve(begin())).get("refresh_token").stringValue();
    try (var executor = Executors.newFixedThreadPool(2)) {
      var start = new CountDownLatch(1);
      Callable<MockHttpServletResponse> rotate =
          () -> {
            start.await();
            return mvc.perform(refreshRequest(refresh)).andReturn().getResponse();
          };
      var first = executor.submit(rotate);
      var second = executor.submit(rotate);
      start.countDown();
      var responses = List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
      assertThat(responses.stream().map(response -> response.getStatus()))
          .containsExactlyInAnyOrder(200, 400);
      String winner =
          mapper
              .readTree(
                  responses.stream()
                      .filter(response -> response.getStatus() == 200)
                      .findFirst()
                      .orElseThrow()
                      .getContentAsString())
              .get("refresh_token")
              .stringValue();
      mvc.perform(refreshRequest(winner)).andExpect(status().isBadRequest());
    }
  }

  @Test
  void reapprovalReplacesTheGrantAndRevokesItsTokens() throws Exception {
    String id = begin();
    String previousCode = approve(id);
    JsonNode previous = exchange(previousCode);
    mvc.perform(approveRequest(id))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error").value("interaction_decided"));
    String replacementCode = approve(begin());
    JsonNode replacement = exchange(replacementCode);
    mvc.perform(refreshRequest(previous.get("refresh_token").stringValue()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("invalid_grant"));
    mvc.perform(exchangeRequest(previousCode)).andExpect(status().isBadRequest());
    refresh(replacement.get("refresh_token").stringValue());
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM mcp_grants WHERE user_id = ? AND revoked_at IS NULL",
                Integer.class,
                userId))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM mcp_grants WHERE user_id = ? AND revoked_at IS NOT NULL",
                Integer.class,
                userId))
        .isEqualTo(1);
  }

  @Test
  void concurrentReapprovalsLeaveExactlyOneActiveGrant() throws Exception {
    String firstId = begin();
    String secondId = begin();
    try (var executor = Executors.newFixedThreadPool(2)) {
      var start = new CountDownLatch(1);
      var first =
          executor.submit(
              () -> {
                start.await();
                return approve(firstId);
              });
      var second =
          executor.submit(
              () -> {
                start.await();
                return approve(secondId);
              });
      start.countDown();
      String firstCode = first.get(20, TimeUnit.SECONDS);
      String secondCode = second.get(20, TimeUnit.SECONDS);
      List<Integer> statuses =
          List.of(
              mvc.perform(exchangeRequest(firstCode)).andReturn().getResponse().getStatus(),
              mvc.perform(exchangeRequest(secondCode)).andReturn().getResponse().getStatus());
      assertThat(statuses).containsExactlyInAnyOrder(200, 400);
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM mcp_grants WHERE user_id = ? AND revoked_at IS NULL",
                  Integer.class,
                  userId))
          .isEqualTo(1);
    }
  }

  @Test
  void concurrentFirstApprovalsInDifferentWorkspacesBothSucceed() throws Exception {
    UUID otherWorkspace = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO workspaces (id, name, slug) VALUES (?, 'Other workspace', ?)",
        otherWorkspace,
        otherWorkspace.toString());
    when(memberships.roleOf(otherWorkspace, userId)).thenReturn(Optional.of(WorkspaceRole.MEMBER));
    String firstId = begin();
    String secondId = begin();
    try (var executor = Executors.newFixedThreadPool(2);
        var blocker = jdbc.getDataSource().getConnection()) {
      blocker.setAutoCommit(false);
      try (var statement = blocker.createStatement()) {
        // Hold INSERTs so both requests reach the consent persistence boundary before release.
        statement.execute("LOCK TABLE oauth2_authorization_consent IN SHARE MODE");
      }
      var first = executor.submit(() -> approve(firstId));
      var second = executor.submit(() -> approve(secondId, otherWorkspace));
      try {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        int waiting = 0;
        while (waiting < 2 && System.nanoTime() < deadline) {
          waiting =
              jdbc.queryForObject(
                  "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() "
                      + "AND wait_event_type = 'Lock' AND (query LIKE 'INSERT INTO oauth2_authorization_consent%' "
                      + "OR query LIKE 'SELECT 1 FROM pg_advisory_xact_lock%')",
                  Integer.class);
          if (waiting < 2) {
            Thread.sleep(20);
          }
        }
        assertThat(waiting)
            .as("Both approvals reached the consent write/serialization boundary")
            .isEqualTo(2);
      } finally {
        blocker.rollback();
      }
      exchange(first.get(20, TimeUnit.SECONDS));
      exchange(second.get(20, TimeUnit.SECONDS));
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM mcp_grants WHERE user_id = ? AND revoked_at IS NULL",
                  Integer.class,
                  userId))
          .isEqualTo(2);
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM oauth2_authorization_consent WHERE principal_name = ?",
                  Integer.class,
                  userId.toString()))
          .isEqualTo(1);
    }
  }

  @Test
  void expiredCodeCannotBeExchanged() throws Exception {
    String code = approve(begin());
    jdbc.update(
        "UPDATE oauth2_authorization SET authorization_code_issued_at = NOW() - INTERVAL '1 hour', authorization_code_expires_at = NOW() - INTERVAL '1 second' WHERE principal_name = ?",
        userId.toString());
    mvc.perform(exchangeRequest(code))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("invalid_grant"));
  }

  @Test
  void expiredRefreshCannotBeRotated() throws Exception {
    String refresh = exchange(approve(begin())).get("refresh_token").stringValue();
    jdbc.update(
        "UPDATE oauth2_authorization SET refresh_token_issued_at = NOW() - INTERVAL '1 hour', refresh_token_expires_at = NOW() - INTERVAL '1 second' WHERE principal_name = ?",
        userId.toString());
    mvc.perform(refreshRequest(refresh))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("invalid_grant"));
  }

  @Test
  void preservesRegisteredQueryAndOpaqueState() throws Exception {
    String callback = REDIRECT + "?fixed=a%2Fb";
    String registered =
        mvc.perform(
                post("/api/oauth2/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        mapper.writeValueAsString(
                            Map.of("client_name", "query", "redirect_uris", List.of(callback)))))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    clientId = mapper.readTree(registered).get("client_id").stringValue();
    String state = "a&b=+ /한글";
    String consent =
        mvc.perform(authorizeRequest(Map.of("redirect_uri", callback, "state", state)))
            .andExpect(status().isFound())
            .andReturn()
            .getResponse()
            .getRedirectedUrl();
    String id =
        UriComponentsBuilder.fromUriString(consent)
            .build()
            .getQueryParams()
            .getFirst("interaction");
    String body =
        mvc.perform(approveRequest(id))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String redirect = mapper.readTree(body).get("redirect_to").stringValue();
    assertThat(redirect).contains("fixed=a%2Fb");
    String returnedState =
        UriComponentsBuilder.fromUriString(redirect).build().getQueryParams().getFirst("state");
    assertThat(URLDecoder.decode(returnedState, StandardCharsets.UTF_8)).isEqualTo(state);
  }

  @Test
  void missingClientAuthenticationReturnsAnOAuthError() throws Exception {
    String code = approve(begin());
    mvc.perform(
            exchangeRequest(code)
                .with(
                    request -> {
                      request.removeParameter("client_id");
                      return request;
                    }))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("invalid_request"));
    exchange(code);
  }

  @Test
  void tokensRemainBoundToTheOriginallyApprovedResource() throws Exception {
    String refresh = exchange(approve(begin())).get("refresh_token").stringValue();
    jdbc.update(
        "UPDATE oauth2_authorization SET attributes = REPLACE(attributes, ?, ?) WHERE principal_name = ?",
        RESOURCE,
        "https://old.example/api/mcp",
        userId.toString());
    mvc.perform(refreshRequest(refresh))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("invalid_target"));
  }

  private MockHttpServletRequestBuilder authorizeRequest() throws Exception {
    return authorizeRequest(Map.of());
  }

  private MockHttpServletRequestBuilder authorizeRequest(Map<String, String> overrides)
      throws Exception {
    String challenge =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                MessageDigest.getInstance("SHA-256")
                    .digest(VERIFIER.getBytes(StandardCharsets.US_ASCII)));
    Map<String, String> params =
        new LinkedHashMap<>(
            Map.of(
                "response_type",
                "code",
                "client_id",
                clientId,
                "redirect_uri",
                REDIRECT,
                "resource",
                RESOURCE,
                "scope",
                "mcp:projects:read",
                "code_challenge_method",
                "S256",
                "code_challenge",
                challenge,
                "state",
                "client-state"));
    params.putAll(overrides);
    MockHttpServletRequestBuilder builder = get("/api/oauth2/authorize");
    params.forEach(builder::queryParam);
    return builder;
  }

  private String begin() throws Exception {
    String url =
        mvc.perform(authorizeRequest())
            .andExpect(status().isFound())
            .andReturn()
            .getResponse()
            .getRedirectedUrl();
    assertThat(url).startsWith("https://app.example.com/oauth/authorize?interaction=");
    String id =
        UriComponentsBuilder.fromUriString(url).build().getQueryParams().getFirst("interaction");
    mvc.perform(get("/api/oauth/interactions/" + id).with(user(userId.toString())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.interaction.client_name").value("test"));
    return id;
  }

  private MockHttpServletRequestBuilder approveRequest(String id) {
    return approveRequest(id, workspaceId);
  }

  private MockHttpServletRequestBuilder approveRequest(String id, UUID approvedWorkspace) {
    return post("/api/oauth/interactions/" + id + "/approve")
        .with(user(userId.toString()))
        .contentType(MediaType.APPLICATION_JSON)
        .content(mapper.writeValueAsString(Map.of("workspace_id", approvedWorkspace)));
  }

  private String approve(String id) throws Exception {
    return approve(id, workspaceId);
  }

  private String approve(String id, UUID approvedWorkspace) throws Exception {
    String body =
        mvc.perform(approveRequest(id, approvedWorkspace))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String redirect = mapper.readTree(body).get("redirect_to").stringValue();
    return UriComponentsBuilder.fromUriString(redirect).build().getQueryParams().getFirst("code");
  }

  private MockHttpServletRequestBuilder exchangeRequest(String code) {
    return post("/api/oauth2/token")
        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
        .param("grant_type", "authorization_code")
        .param("client_id", clientId)
        .param("code", code)
        .param("redirect_uri", REDIRECT)
        .param("code_verifier", VERIFIER)
        .param("resource", RESOURCE);
  }

  private JsonNode exchange(String code) throws Exception {
    return mapper.readTree(
        mvc.perform(exchangeRequest(code))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString());
  }

  private MockHttpServletRequestBuilder refreshRequest(String refresh) {
    return post("/api/oauth2/token")
        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
        .param("grant_type", "refresh_token")
        .param("client_id", clientId)
        .param("refresh_token", refresh)
        .param("resource", RESOURCE);
  }

  private JsonNode refresh(String refresh) throws Exception {
    return mapper.readTree(
        mvc.perform(refreshRequest(refresh))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString());
  }

  @TestConfiguration
  static class Config {
    @Bean
    Clock clock() {
      return Clock.systemUTC();
    }

    @Bean
    SecurityFilterChain interactionTestChain(HttpSecurity http) throws Exception {
      return http.securityMatcher("/api/oauth/interactions/**")
          // Test-only substitute for auth's SameSite-cookie policy (ADR-0003).
          // McpOAuthSecurityIntegrationTest verifies the real authenticated/CORS app chain.
          .csrf(AbstractHttpConfigurer::disable)
          .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
          .build();
    }
  }
}
