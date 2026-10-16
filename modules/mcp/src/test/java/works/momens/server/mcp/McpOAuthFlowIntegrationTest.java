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
import java.util.HexFormat;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
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
import works.momens.server.mcp.transport.McpAuthenticationContext;
import works.momens.server.mcp.transport.McpBearerTokenVerifier;
import works.momens.server.project.core.ProjectDetailReader;
import works.momens.server.project.core.ProjectReader;
import works.momens.server.project.milestone.MilestoneReader;
import works.momens.server.project.milestone.MilestoneWriter;
import works.momens.server.project.task.TaskReader;
import works.momens.server.project.task.TaskWriter;
import works.momens.server.project.taskupdate.TaskUpdateReader;
import works.momens.server.project.taskupdate.TaskUpdateWriter;
import works.momens.server.user.UserService;
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
@DisplayName("MCP OAuth 인가·토큰 수명주기 통합 테스트")
class McpOAuthFlowIntegrationTest extends AbstractPostgresIntegrationTest {
  private static final String RESOURCE = "https://api.momens.works/api/mcp";
  private static final String REDIRECT = "http://localhost:3000/callback";
  private static final String VERIFIER = "a".repeat(43);
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired McpGrantWriter grants;
  @Autowired McpBearerTokenVerifier tokenVerifier;
  @MockitoBean ProjectDetailReader projects;
  @MockitoBean ProjectReader projectAccess;
  @MockitoBean MilestoneReader milestones;
  @MockitoBean TaskReader tasks;
  @MockitoBean TaskWriter taskWriter;
  @MockitoBean MilestoneWriter milestoneWriter;
  @MockitoBean TaskUpdateWriter taskUpdateWriter;
  @MockitoBean TaskUpdateReader updates;
  @MockitoBean UserService users;
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
  @DisplayName("코드와 토큰 원문을 저장하지 않고 public client 토큰을 발급·갱신·폐기한다")
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
  @ExtendWith(OutputCaptureExtension.class)
  @DisplayName("refresh token 재사용 시 현재 token family와 access token을 폐기한다")
  void refreshReuseRevokesTheCurrentFamilyAndAccessToken(CapturedOutput output) throws Exception {
    JsonNode pair = exchange(approve(begin()));
    String refresh = pair.get("refresh_token").stringValue();
    JsonNode rotated = refresh(refresh);
    mvc.perform(refreshRequest(refresh))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("invalid_grant"));
    mvc.perform(refreshRequest(refresh)).andExpect(status().isBadRequest());
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
    String presentedTokenId =
        HexFormat.of()
            .formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(refresh.getBytes(StandardCharsets.UTF_8)),
                0,
                8);
    String nextTokenId =
        HexFormat.of()
            .formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(
                        rotated
                            .get("refresh_token")
                            .stringValue()
                            .getBytes(StandardCharsets.UTF_8)),
                0,
                8);
    List<String> rejected =
        output
            .getAll()
            .lines()
            .filter(
                line ->
                    line.contains(
                        "event=mcp_oauth_refresh outcome=rejected reason=family_inactive"))
            .toList();
    assertThat(rejected)
        .hasSize(2)
        .anyMatch(line -> line.contains("presented_token_id=" + presentedTokenId))
        .anyMatch(line -> line.contains("presented_token_id=" + nextTokenId));
    assertThat(output.getAll())
        .contains("event=mcp_oauth_refresh outcome=succeeded")
        .contains("event=mcp_oauth_refresh outcome=family_revoked reason=refresh_token_reused")
        .contains("presented_token_id=" + presentedTokenId, "next_token_id=" + nextTokenId)
        .doesNotContain(
            "presented_token_id=" + refresh.substring(0, 16),
            "next_token_id=" + rotated.get("refresh_token").stringValue().substring(0, 16))
        .doesNotContain(refresh, rotated.get("refresh_token").stringValue());
  }

  @Test
  @DisplayName("access token 만료 후 여러 차례 갱신해도 재동의 없이 MCP를 계속 사용한다")
  void consecutiveRefreshesKeepTheGrantUsable() throws Exception {
    JsonNode pair = exchange(approve(begin()));
    for (int attempt = 0; attempt < 3; attempt++) {
      String previousAccess = pair.get("access_token").stringValue();
      jdbc.update(
          "UPDATE oauth2_authorization SET access_token_issued_at = now() - interval '1 hour', access_token_expires_at = now() - interval '1 second' WHERE principal_name = ?",
          userId.toString());
      mvc.perform(mcpRequest(previousAccess, "tools/list")).andExpect(status().isUnauthorized());
      pair = refresh(pair.get("refresh_token").stringValue());
      mvc.perform(mcpRequest(pair.get("access_token").stringValue(), "tools/list"))
          .andExpect(status().isOk());
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM mcp_grants WHERE user_id = ? AND revoked_at IS NULL",
                Integer.class,
                userId))
        .isEqualTo(1);
  }

  @Test
  @DisplayName("한 MCP client의 refresh 재사용은 다른 client의 연결을 폐기하지 않는다")
  void refreshReuseDoesNotRevokeAnotherClient() throws Exception {
    String firstClient = clientId;
    JsonNode first = exchange(approve(begin()));
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
    String secondClient = mapper.readTree(body).get("client_id").stringValue();
    clientId = secondClient;
    JsonNode second = exchange(approve(begin()));

    clientId = firstClient;
    JsonNode firstRotated = refresh(first.get("refresh_token").stringValue());
    mvc.perform(refreshRequest(first.get("refresh_token").stringValue()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("invalid_grant"));
    mvc.perform(mcpRequest(firstRotated.get("access_token").stringValue(), "tools/list"))
        .andExpect(status().isUnauthorized());

    clientId = secondClient;
    JsonNode rotated = refresh(second.get("refresh_token").stringValue());
    mvc.perform(mcpRequest(rotated.get("access_token").stringValue(), "tools/list"))
        .andExpect(status().isOk());
  }

  @Test
  @DisplayName("인가 코드 재사용과 잘못된 verifier·resource·redirect URI를 거부한다")
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
  @DisplayName("거부되거나 만료된 동의 요청에는 인가 코드를 발급하지 않는다")
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
  @DisplayName("grant 철회 시 실제 token family 어댑터를 통해 토큰을 폐기한다")
  void grantRevocationUsesTheRealFamilyAdapter() throws Exception {
    JsonNode pair = exchange(approve(begin()));
    String access = pair.get("access_token").stringValue();
    mvc.perform(mcpRequest(access, "tools/list")).andExpect(status().isOk());
    UUID grantId =
        jdbc.queryForObject("SELECT id FROM mcp_grants WHERE user_id = ?", UUID.class, userId);
    grants.revoke(grantId, null);
    mvc.perform(mcpRequest(access, "tools/list")).andExpect(status().isUnauthorized());
    mvc.perform(refreshRequest(pair.get("refresh_token").stringValue()))
        .andExpect(status().isBadRequest());
  }

  @Test
  @ExtendWith(OutputCaptureExtension.class)
  @DisplayName("reference token으로 MCP를 인증하고 갱신 후 이전 access token을 거부한다")
  void referenceTokensAuthenticateTransportAndRotationRejectsThePreviousAccessToken(
      CapturedOutput output) throws Exception {
    String code = approve(begin());
    JsonNode pair = exchange(code);
    String access = pair.get("access_token").stringValue();
    McpAuthenticationContext context = tokenVerifier.verify(access).orElseThrow();
    assertThat(context.userId()).isEqualTo(userId);
    assertThat(context.workspaceId()).isEqualTo(workspaceId);
    assertThat(context.clientId()).isEqualTo(clientId);
    assertThat(context.scopes()).containsExactly("mcp:projects:read");
    mvc.perform(mcpRequest(access, "server/discover"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.result.supportedVersions[0]").value("2026-07-28"));
    mvc.perform(mcpRequest(access, "tools/list"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.result.tools[0].name").value("list_projects"));
    String digest =
        jdbc.queryForObject(
            "SELECT access_token_value FROM oauth2_authorization WHERE principal_name = ?",
            String.class,
            userId.toString());
    for (String rejected : List.of(code, pair.get("refresh_token").stringValue(), digest)) {
      mvc.perform(mcpRequest(rejected, "tools/list"))
          .andExpect(status().isUnauthorized())
          .andExpect(
              header()
                  .string("WWW-Authenticate", containsString("oauth-protected-resource/api/mcp")));
    }
    JsonNode rotated = refresh(pair.get("refresh_token").stringValue());
    mvc.perform(mcpRequest(access, "tools/list")).andExpect(status().isUnauthorized());
    String nextAccess = rotated.get("access_token").stringValue();
    mvc.perform(mcpRequest(nextAccess, "tools/list")).andExpect(status().isOk());
    mvc.perform(refreshRequest(pair.get("refresh_token").stringValue()))
        .andExpect(status().isBadRequest());
    mvc.perform(mcpRequest(nextAccess, "tools/list")).andExpect(status().isUnauthorized());
    assertThat(output.getAll())
        .doesNotContain(
            code,
            digest,
            access,
            nextAccess,
            pair.get("refresh_token").stringValue(),
            rotated.get("refresh_token").stringValue());
  }

  @Test
  @DisplayName("MCP 호출마다 토큰 만료와 멤버십 삭제를 확인해 접근을 거부한다")
  void transportRejectsExpiredTokensAndRemovedMembershipWithoutCaching() throws Exception {
    String access = exchange(approve(begin())).get("access_token").stringValue();
    mvc.perform(mcpRequest(access, "tools/list")).andExpect(status().isOk());
    when(memberships.roleOf(workspaceId, userId)).thenReturn(Optional.empty());
    mvc.perform(mcpRequest(access, "tools/list")).andExpect(status().isUnauthorized());
    when(memberships.roleOf(workspaceId, userId)).thenReturn(Optional.of(WorkspaceRole.MEMBER));
    jdbc.update(
        "UPDATE oauth2_authorization SET access_token_issued_at = now() - interval '1 hour', access_token_expires_at = now() - interval '1 second' WHERE principal_name = ?",
        userId.toString());
    mvc.perform(mcpRequest(access, "tools/list")).andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("grant나 token family가 별도로 철회되면 활성 토큰의 MCP 접근을 거부한다")
  void transportRejectsAnActiveTokenWhenTheGrantOrFamilyIsRevokedIndependently() throws Exception {
    String access = exchange(approve(begin())).get("access_token").stringValue();
    mvc.perform(mcpRequest(access, "tools/list")).andExpect(status().isOk());
    jdbc.update("UPDATE mcp_grants SET revoked_at = now() WHERE user_id = ?", userId);
    mvc.perform(mcpRequest(access, "tools/list")).andExpect(status().isUnauthorized());
    String nextAccess = exchange(approve(begin())).get("access_token").stringValue();
    mvc.perform(mcpRequest(nextAccess, "tools/list")).andExpect(status().isOk());
    jdbc.update(
        "UPDATE mcp_token_families SET revoked_at = now() WHERE grant_id IN (SELECT id FROM mcp_grants WHERE user_id = ?)",
        userId);
    mvc.perform(mcpRequest(nextAccess, "tools/list")).andExpect(status().isUnauthorized());
  }

  private MockHttpServletRequestBuilder mcpRequest(String access, String method) {
    return post("/api/mcp")
        .header("Authorization", "Bearer " + access)
        .header("MCP-Protocol-Version", "2026-07-28")
        .header("Mcp-Method", method)
        .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
        .contentType(MediaType.APPLICATION_JSON)
        .content(
            mapper.writeValueAsString(
                Map.of(
                    "jsonrpc",
                    "2.0",
                    "id",
                    1,
                    "method",
                    method,
                    "params",
                    Map.of(
                        "_meta",
                        Map.of(
                            "io.modelcontextprotocol/protocolVersion",
                            "2026-07-28",
                            "io.modelcontextprotocol/clientCapabilities",
                            Map.of())))));
  }

  @Test
  @DisplayName("잘못된 principal은 표준 AUTH_INVALID_TOKEN 오류로 응답한다")
  void malformedPrincipalPreservesStandardAuthError() throws Exception {
    mvc.perform(get("/api/oauth/interactions/" + UUID.randomUUID()).with(user("not-a-uuid")))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error.code").value("AUTH_INVALID_TOKEN"));
  }

  @Test
  @DisplayName("존재하지 않는 동의 요청은 기존 invalid_interaction 오류로 거부한다")
  void rejectsUnknownInteractionWithLegacyError() throws Exception {
    mvc.perform(approveRequest(UUID.randomUUID().toString()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("invalid_interaction"));
  }

  @Test
  @DisplayName("워크스페이스 비멤버의 동의 승인을 거부한다")
  void rejectsNonMemberConsent() throws Exception {
    String id = begin();
    when(memberships.roleOf(workspaceId, userId)).thenReturn(Optional.empty());
    mvc.perform(approveRequest(id))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error").value("forbidden"));
  }

  @Test
  @DisplayName("동의 화면으로 이동하기 전에 client와 redirect URI를 검증한다")
  void validatesAuthorizeBeforeRedirectingToConsent() throws Exception {
    mvc.perform(authorizeRequest(Map.of("redirect_uri", "https://evil.example/callback")))
        .andExpect(status().isBadRequest());
    mvc.perform(authorizeRequest(Map.of("client_id", "unknown")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("invalid_request"));
  }

  @Test
  @DisplayName("잘못된 resource와 scope의 구체적인 인가 오류를 유지한다")
  void preservesSpecificAuthorizationErrors() throws Exception {
    mvc.perform(authorizeRequest(Map.of("resource", "https://wrong.example/api/mcp")))
        .andExpect(status().isFound())
        .andExpect(header().string("Location", containsString("error=invalid_target")));
    mvc.perform(authorizeRequest(Map.of("scope", "unknown:scope")))
        .andExpect(status().isFound())
        .andExpect(header().string("Location", containsString("error=invalid_scope")));
  }

  @Test
  @DisplayName("잘못된 PKCE·resource·scope·request_uri와 미등록 loopback 포트를 거부한다")
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
  @DisplayName("다른 client의 토큰 사용을 거부하고 원래 소유자의 token family를 유지한다")
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
  @DisplayName("토큰 갱신 시 scope 확대와 멤버십 삭제를 감지해 거부한다")
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
  @DisplayName("같은 인가 코드의 동시 교환은 한 번만 성공한다")
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
  @DisplayName("동시 토큰 갱신에서 재사용을 감지하고 먼저 발급된 토큰도 폐기한다")
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
  @DisplayName("재승인 시 기존 grant를 교체하고 연결된 토큰을 폐기한다")
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
  @DisplayName("동시 재승인 후 활성 grant는 하나만 남는다")
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
  @DisplayName("서로 다른 워크스페이스의 동시 최초 승인은 모두 성공한다")
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
  @DisplayName("만료된 인가 코드는 토큰으로 교환할 수 없다")
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
  @DisplayName("만료된 refresh token으로 토큰을 갱신할 수 없다")
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
  @DisplayName("등록된 redirect URI의 쿼리와 state 원문을 보존한다")
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
  @DisplayName("client 인증 정보가 없으면 OAuth 오류를 반환하고 코드는 유지한다")
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
  @DisplayName("토큰 갱신은 최초 승인된 resource에만 허용한다")
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
