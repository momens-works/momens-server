package works.momens.server.support.security;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import jakarta.servlet.http.Cookie;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import works.momens.server.auth.AccessTokenTestFactory;
import works.momens.server.common.test.AbstractPostgresIntegrationTest;
import works.momens.server.mcp.grant.McpTokenFamilyRevoker;

@SpringBootTest(properties = "momens.mcp.oauth.consent-uri=https://app.example.com/oauth/authorize")
@AutoConfigureMockMvc
@DisplayName("워크스페이스 MCP 연결 조회·폐기 통합 테스트")
class McpGrantWebIntegrationTest extends AbstractPostgresIntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired DataSource dataSource;
  @Autowired AccessTokenTestFactory accessTokens;
  @MockitoSpyBean McpTokenFamilyRevoker revoker;

  @Test
  @DisplayName("내 활성 연결만 조회하고 사용 시각을 기록하며 폐기 즉시 access·refresh를 거부한다")
  void listsOwnConnectionsAndRevokesTokensTogether() throws Exception {
    UUID user = user();
    UUID workspace = workspace(user);
    Session session = authorize(user, workspace);
    Session otherUser = authorize(user(), workspace);
    Session otherWorkspace = authorize(user, workspace(user));
    Cookie cookie = cookie(user);
    String path = path(workspace);
    OpenApiInteractionValidator validator = validator();
    mvc.perform(get(path).cookie(cookie))
        .andExpect(status().isOk())
        .andExpect(openApi().isValid(validator))
        .andExpect(jsonPath("$.grants.length()").value(1))
        .andExpect(jsonPath("$.grants[0].id").value(session.grant().toString()))
        .andExpect(jsonPath("$.grants[0].client_id").value(session.client()))
        .andExpect(jsonPath("$.grants[0].client_name").value("Grant test"))
        .andExpect(jsonPath("$.grants[0].user_id").value(user.toString()))
        .andExpect(jsonPath("$.grants[0].workspace_id").value(workspace.toString()))
        .andExpect(jsonPath("$.grants[0].scopes").isArray())
        .andExpect(jsonPath("$.grants[0].created_at").exists())
        .andExpect(jsonPath("$.grants[0].last_used_at").doesNotExist())
        .andExpect(jsonPath("$.grants[0].revoked_at").doesNotExist());
    mvc.perform(mcp(session.access())).andExpect(status().isOk());
    Instant used = lastUsed(session.grant());
    assertThat(used).isNotNull();
    mvc.perform(get(path).cookie(cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.grants[0].last_used_at").exists());
    assertThat(lastUsed(session.grant())).isEqualTo(used);
    mvc.perform(delete(path + "/" + session.grant()).cookie(cookie))
        .andExpect(status().isNoContent())
        .andExpect(openApi().isValid(validator))
        .andExpect(content().string(""));
    mvc.perform(mcp(session.access())).andExpect(status().isUnauthorized());
    mvc.perform(refresh(session))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("invalid_grant"));
    assertThat(lastUsed(session.grant())).isEqualTo(used);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM mcp_token_families WHERE grant_id = ? AND revoked_at IS NULL",
                Integer.class,
                session.grant()))
        .isZero();
    mvc.perform(get(path).cookie(cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.grants").isEmpty());
    mvc.perform(delete(path + "/" + session.grant()).cookie(cookie))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error").value("forbidden"))
        .andExpect(openApi().isValid(validator));
    mvc.perform(mcp(otherUser.access())).andExpect(status().isOk());
    mvc.perform(mcp(otherWorkspace.access())).andExpect(status().isOk());
  }

  @Test
  @DisplayName("비멤버·다른 사용자·다른 워크스페이스는 연결을 조회하거나 폐기할 수 없다")
  void rejectsCrossWorkspaceAndCrossUserAccess() throws Exception {
    UUID owner = user();
    UUID workspace = workspace(owner);
    Session session = authorize(owner, workspace);
    UUID member = user();
    jdbc.update(
        "INSERT INTO workspace_members (workspace_id, user_id, role) VALUES (?, ?, 'owner')",
        workspace,
        member);
    Cookie ownerCookie = cookie(owner);
    Cookie memberCookie = cookie(member);
    Cookie outsider = cookie(user());
    String path = path(workspace);
    mvc.perform(get(path)).andExpect(status().isUnauthorized());
    mvc.perform(delete(path + "/" + session.grant())).andExpect(status().isUnauthorized());
    mvc.perform(get(path).header("Authorization", "Bearer " + session.access()))
        .andExpect(status().isUnauthorized());
    mvc.perform(get(path).cookie(outsider))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error").value("forbidden"));
    mvc.perform(get(path).cookie(memberCookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.grants").isEmpty());
    for (Cookie denied : List.of(memberCookie, outsider)) {
      mvc.perform(delete(path + "/" + session.grant()).cookie(denied))
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.error").value("forbidden"));
    }
    UUID otherWorkspace = workspace(owner);
    mvc.perform(delete(path(otherWorkspace) + "/" + session.grant()).cookie(ownerCookie))
        .andExpect(status().isForbidden());
    mvc.perform(delete(path + "/" + UUID.randomUUID()).cookie(ownerCookie))
        .andExpect(status().isForbidden());
    mvc.perform(delete(path + "/invalid").cookie(ownerCookie))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("invalid grant id"));
    mvc.perform(get("/api/workspaces/invalid/mcp-grants").cookie(ownerCookie))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("invalid workspace id"));
    mvc.perform(
            delete(path + "/" + session.grant())
                .cookie(ownerCookie)
                .header("Origin", "https://evil.example"))
        .andExpect(status().isForbidden());
    mvc.perform(mcp(session.access())).andExpect(status().isOk());
    Instant used = lastUsed(session.grant());
    jdbc.update(
        "DELETE FROM workspace_members WHERE workspace_id = ? AND user_id = ?", workspace, owner);
    mvc.perform(delete(path + "/" + session.grant()).cookie(ownerCookie))
        .andExpect(status().isForbidden());
    mvc.perform(mcp(session.access())).andExpect(status().isUnauthorized());
    assertThat(lastUsed(session.grant())).isEqualTo(used);
    assertThat(
            jdbc.queryForObject(
                "SELECT revoked_at IS NULL FROM mcp_grants WHERE id = ?",
                Boolean.class,
                session.grant()))
        .isTrue();
  }

  @Test
  @DisplayName("토큰 폐기 저장 실패 시 grant 폐기도 rollback한다")
  void rollsBackGrantWhenTokenRevocationFails() throws Exception {
    UUID user = user();
    UUID workspace = workspace(user);
    Session session = authorize(user, workspace);
    doThrow(new DataAccessResourceFailureException("private database detail"))
        .when(revoker)
        .revokeByGrantId(eq(session.grant()), any());
    String body =
        mvc.perform(delete(path(workspace) + "/" + session.grant()).cookie(cookie(user)))
            .andExpect(status().isInternalServerError())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(body).doesNotContain("private database detail");
    assertThat(
            jdbc.queryForObject(
                "SELECT revoked_at IS NULL FROM mcp_grants WHERE id = ?",
                Boolean.class,
                session.grant()))
        .isTrue();
    mvc.perform(mcp(session.access())).andExpect(status().isOk());
    mvc.perform(refresh(session)).andExpect(status().isOk());
  }

  @Test
  @DisplayName("동시에 읽은 활성 연결의 폐기는 한 요청만 성공하고 최초 폐기 시각을 보존한다")
  void concurrentDeletesHaveOneWinner() throws Exception {
    UUID user = user();
    UUID workspace = workspace(user);
    Session session = authorize(user, workspace);
    Cookie cookie = cookie(user);
    mvc.perform(mcp(session.access())).andExpect(status().isOk());
    Instant used = lastUsed(session.grant());

    try (var connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      JdbcTemplate locker = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
      int lockerPid = locker.queryForObject("SELECT pg_backend_pid()", Integer.class);
      // Both implementations must read an active grant before either can commit:
      // entity-based revoke waits on authorization, conditional UPDATE waits on the grant.
      locker.queryForList("SELECT id FROM mcp_grants WHERE id = ? FOR UPDATE", session.grant());
      locker.queryForList(
          "SELECT id FROM oauth2_authorization WHERE id IN "
              + "(SELECT authorization_id FROM mcp_token_families WHERE grant_id = ?) FOR UPDATE",
          session.grant());
      try (var executor = Executors.newFixedThreadPool(2)) {
        var first =
            executor.submit(
                () ->
                    mvc.perform(delete(path(workspace) + "/" + session.grant()).cookie(cookie))
                        .andReturn()
                        .getResponse());
        var second =
            executor.submit(
                () ->
                    mvc.perform(delete(path(workspace) + "/" + session.grant()).cookie(cookie))
                        .andReturn()
                        .getResponse());
        try {
          long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
          int blocked;
          do {
            blocked =
                jdbc.queryForObject(
                    "WITH RECURSIVE blocked AS ("
                        + "SELECT pid FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid)) "
                        + "UNION SELECT a.pid FROM pg_stat_activity a JOIN blocked b "
                        + "ON b.pid = ANY(pg_blocking_pids(a.pid))) SELECT count(*) FROM blocked",
                    Integer.class,
                    lockerPid);
            if (blocked >= 2) {
              break;
            }
            Thread.sleep(20);
          } while (System.nanoTime() < deadline);
          assertThat(blocked)
              .as("both DELETE transactions reached their database lock")
              .isEqualTo(2);
        } finally {
          connection.rollback();
        }
        var responses = List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
        assertThat(responses)
            .extracting(response -> response.getStatus())
            .containsExactlyInAnyOrder(204, 403);
        String rejected =
            responses.stream()
                .filter(response -> response.getStatus() == 403)
                .findFirst()
                .orElseThrow()
                .getContentAsString();
        assertThat(mapper.readTree(rejected).path("error").stringValue()).isEqualTo("forbidden");
      }
    }
    Timestamp revokedAt =
        jdbc.queryForObject(
            "SELECT revoked_at FROM mcp_grants WHERE id = ?", Timestamp.class, session.grant());
    assertThat(revokedAt)
        .isNotNull()
        .isEqualTo(
            jdbc.queryForObject(
                "SELECT revoked_at FROM mcp_token_families WHERE grant_id = ?",
                Timestamp.class,
                session.grant()));
    assertThat(lastUsed(session.grant())).isEqualTo(used);
    mvc.perform(mcp(session.access())).andExpect(status().isUnauthorized());
    mvc.perform(refresh(session))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("invalid_grant"));
  }

  private OpenApiInteractionValidator validator() throws Exception {
    String spec =
        mvc.perform(get("/api/v3/api-docs"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return OpenApiInteractionValidator.createForInlineApiSpecification(spec).build();
  }

  private UUID user() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO users (id, email, name) VALUES (?, ?, 'Grant user')", id, id + "@example.com");
    return id;
  }

  private UUID workspace(UUID user) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO workspaces (id, name, slug) VALUES (?, 'Grant workspace', ?)",
        id,
        id.toString());
    jdbc.update(
        "INSERT INTO workspace_members (workspace_id, user_id, role) VALUES (?, ?, 'member')",
        id,
        user);
    return id;
  }

  private Cookie cookie(UUID user) {
    return new Cookie("access_token", accessTokens.issueAccessToken(user));
  }

  private String path(UUID workspace) {
    return "/api/workspaces/" + workspace + "/mcp-grants";
  }

  private Instant lastUsed(UUID grant) {
    Timestamp timestamp =
        jdbc.queryForObject(
            "SELECT last_used_at FROM mcp_grants WHERE id = ?", Timestamp.class, grant);
    return timestamp == null ? null : timestamp.toInstant();
  }

  private Session authorize(UUID user, UUID workspace) throws Exception {
    jdbc.update(
        "INSERT INTO workspace_members (workspace_id, user_id, role) VALUES (?, ?, 'member') ON CONFLICT DO NOTHING",
        workspace,
        user);
    JsonNode registration =
        mapper.readTree(
            mvc.perform(
                    post("/api/oauth2/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            mapper.writeValueAsString(
                                Map.of(
                                    "client_name",
                                    "Grant test",
                                    "redirect_uris",
                                    List.of("http://localhost:3000/callback")))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString());
    String client = registration.get("client_id").stringValue();
    String redirect =
        mvc.perform(
                get("/api/oauth2/authorize")
                    .queryParam("response_type", "code")
                    .queryParam("client_id", client)
                    .queryParam("redirect_uri", "http://localhost:3000/callback")
                    .queryParam("code_challenge_method", "S256")
                    .queryParam("code_challenge", "ZtNPunH49FD35FWYhT5Tv8I7vRKQJ8uxMaL0_9eHjNA")
                    .queryParam("resource", "https://api.momens.works/api/mcp"))
            .andExpect(status().isFound())
            .andReturn()
            .getResponse()
            .getRedirectedUrl();
    String interaction =
        UriComponentsBuilder.fromUriString(redirect)
            .build()
            .getQueryParams()
            .getFirst("interaction");
    JsonNode approval =
        mapper.readTree(
            mvc.perform(
                    post("/api/oauth/interactions/" + interaction + "/approve")
                        .cookie(cookie(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("workspace_id", workspace))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    String code =
        UriComponentsBuilder.fromUriString(approval.get("redirect_to").stringValue())
            .build()
            .getQueryParams()
            .getFirst("code");
    JsonNode pair =
        mapper.readTree(
            mvc.perform(
                    post("/api/oauth2/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "authorization_code")
                        .param("client_id", client)
                        .param("code", code)
                        .param("code_verifier", "a".repeat(43))
                        .param("redirect_uri", "http://localhost:3000/callback")
                        .param("resource", "https://api.momens.works/api/mcp"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    UUID grant =
        jdbc.queryForObject(
            "SELECT id FROM mcp_grants WHERE client_id = ? AND revoked_at IS NULL",
            UUID.class,
            client);
    return new Session(
        client,
        grant,
        pair.get("access_token").stringValue(),
        pair.get("refresh_token").stringValue());
  }

  private MockHttpServletRequestBuilder refresh(Session session) {
    return post("/api/oauth2/token")
        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
        .param("grant_type", "refresh_token")
        .param("client_id", session.client())
        .param("refresh_token", session.refresh())
        .param("resource", "https://api.momens.works/api/mcp");
  }

  private MockHttpServletRequestBuilder mcp(String token) {
    return post("/api/mcp")
        .header("Authorization", "Bearer " + token)
        .header("MCP-Protocol-Version", "2026-07-28")
        .header("Mcp-Method", "tools/list")
        .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
        .contentType(MediaType.APPLICATION_JSON)
        .content(
            """
            {"jsonrpc":"2.0","id":1,"method":"tools/list","params":{"_meta":{
              "io.modelcontextprotocol/protocolVersion":"2026-07-28",
              "io.modelcontextprotocol/clientCapabilities":{}}}}
            """);
  }

  private record Session(String client, UUID grant, String access, String refresh) {}
}
