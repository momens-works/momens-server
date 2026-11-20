package works.momens.server.source.connection.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import works.momens.server.auth.AccessTokenTestFactory;
import works.momens.server.common.api.BusinessException;
import works.momens.server.common.test.AbstractPostgresIntegrationTest;
import works.momens.server.source.ConfigureFigmaCommand;
import works.momens.server.source.SourceConnectionWriter;
import works.momens.server.source.SourceErrorCode;

@SpringBootTest(
    properties = {
      "momens.source.oauth.redirect-uri=https://api.example/callback",
      "momens.source.oauth.state-secret=long-enough-state-secret-for-test-hs256",
      "momens.source.oauth.token-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
      "momens.source.oauth.providers.figma.client-id=test-client",
      "momens.source.oauth.providers.figma.client-secret=test-secret",
      "momens.source.figma.webhook.endpoint=https://worker.example/webhooks/figma",
      "momens.source.figma.webhook.passcode=test-passcode"
    })
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("Figma configure 인증·HTTP·DB 통합 테스트")
class WebFigmaConfigureIntegrationTest extends AbstractPostgresIntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired AccessTokenTestFactory tokens;
  @Autowired TokenEncryptor encryptor;
  @Autowired SourceConnectionWriter writer;
  @Autowired TransactionTemplate transactions;
  @MockitoBean FigmaWebhookClient client;
  UUID workspaceId;
  UUID userId;
  UUID connectionId;

  @BeforeEach
  void setUp() {
    workspaceId = UUID.randomUUID();
    userId = UUID.randomUUID();
    connectionId = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO users(id,email,name) VALUES (?,?, 'figma')", userId, userId + "@example.com");
    jdbc.update(
        "INSERT INTO workspaces(id,name,slug) VALUES (?, 'figma', ?)",
        workspaceId,
        "figma-" + workspaceId);
    jdbc.update(
        "INSERT INTO workspace_members(workspace_id,user_id,role) VALUES (?,?,'owner')",
        workspaceId,
        userId);
    jdbc.update(
        "INSERT INTO source_connections(id,workspace_id,source_type,status) VALUES (?,?,'FIGMA','PENDING')",
        connectionId,
        workspaceId);
    jdbc.update(
        "INSERT INTO source_credentials(connection_id,access_token_enc) VALUES (?,?)",
        connectionId,
        encryptor.encrypt("secret-token"));
    when(client.create(anyString(), anyString(), any(), any())).thenReturn("new-webhook");
  }

  @AfterEach
  void tearDown() {
    jdbc.update("DELETE FROM source_connections WHERE id=?", connectionId);
    jdbc.update("DELETE FROM workspace_members WHERE workspace_id=?", workspaceId);
    jdbc.update("DELETE FROM workspaces WHERE id=?", workspaceId);
    jdbc.update("DELETE FROM users WHERE id=?", userId);
  }

  @ParameterizedTest
  @ValueSource(strings = {"admin", "owner"})
  @DisplayName("관리자와 소유자는 설정 후 활성화된 연결을 직접 응답받는다")
  void activates(String role) throws Exception {
    jdbc.update("UPDATE workspace_members SET role=? WHERE workspace_id=?", role, workspaceId);
    mvc.perform(
            request(connectionId)
                .content("{\"team_id\":\" team \",\"file_keys\":[\" f1 \",\"f1\",\"\"]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ACTIVE"))
        .andExpect(jsonPath("$.id").value(connectionId.toString()))
        .andExpect(jsonPath("$.metadata.team_id").value("team"))
        .andExpect(jsonPath("$.metadata.file_keys.length()").value(1))
        .andExpect(jsonPath("$.metadata.webhook_id").value("new-webhook"))
        .andExpect(jsonPath("$.disabled_at").doesNotExist())
        .andExpect(jsonPath("$.data").doesNotExist());
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM source_connections WHERE id=?", String.class, connectionId))
        .isEqualTo("ACTIVE");
  }

  @ParameterizedTest
  @ValueSource(strings = {"member", "outsider"})
  @DisplayName("권한 없는 요청은 provider 호출과 상태 변경 없이 거부한다")
  void deniesRole(String role) throws Exception {
    if (role.equals("outsider"))
      jdbc.update("DELETE FROM workspace_members WHERE workspace_id=?", workspaceId);
    else jdbc.update("UPDATE workspace_members SET role=? WHERE workspace_id=?", role, workspaceId);
    mvc.perform(request(connectionId))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("AUTH_FORBIDDEN"))
        .andExpect(jsonPath("$.error.details.required_role").value("admin"));
    verifyNoInteractions(client);
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM source_connections WHERE id=?", String.class, connectionId))
        .isEqualTo("PENDING");
  }

  @Test
  @DisplayName("인증 없음·식별자 오류·연결 없음은 Standard 오류를 반환한다")
  void invalidRequests() throws Exception {
    mvc.perform(post("/api/source-connections/{id}/figma/configure", connectionId))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error.code").value("AUTH_UNAUTHORIZED"));
    mvc.perform(request("bad-id")).andExpect(status().isBadRequest());
    mvc.perform(request(UUID.randomUUID()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("SOURCE_CONNECTION_NOT_FOUND"));
    verifyNoInteractions(client);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{}",
        "{\"team_id\":\"team\",\"file_keys\":[]}",
        "{\"team_id\":\"team\",\"file_keys\":[\" \" ]}"
      })
  @DisplayName("빈 입력과 정규화 후 빈 허용 목록은 거부한다")
  void validates(String json) throws Exception {
    mvc.perform(request(connectionId).content(json)).andExpect(status().isBadRequest());
    verifyNoInteractions(client);
  }

  @ParameterizedTest
  @ValueSource(strings = {"missing", "expired", "blank"})
  @DisplayName("누락·만료·빈 토큰은 재인증 필요 409를 반환하고 연결을 보존한다")
  void requiresReauthorization(String credentialState) throws Exception {
    switch (credentialState) {
      case "missing" ->
          jdbc.update("DELETE FROM source_credentials WHERE connection_id=?", connectionId);
      case "expired" ->
          jdbc.update(
              "UPDATE source_credentials SET expires_at=now()-interval '1 day' WHERE connection_id=?",
              connectionId);
      case "blank" ->
          jdbc.update(
              "UPDATE source_credentials SET access_token_enc=? WHERE connection_id=?",
              encryptor.encrypt(" "),
              connectionId);
      default -> throw new IllegalArgumentException(credentialState);
    }
    mvc.perform(request(connectionId))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("SOURCE_FIGMA_REAUTH_REQUIRED"));
    verifyNoInteractions(client);
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM source_connections WHERE id=?", String.class, connectionId))
        .isEqualTo("PENDING");
  }

  @Test
  @DisplayName("등록 실패는 안전한 Standard 오류를 반환하고 PENDING을 유지한다")
  void providerFailure() throws Exception {
    when(client.create(anyString(), anyString(), any(), any()))
        .thenThrow(new BusinessException(SourceErrorCode.SOURCE_FIGMA_WEBHOOK_FAILED, Map.of()));
    mvc.perform(request(connectionId))
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.error.code").value("SOURCE_FIGMA_WEBHOOK_FAILED"));
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM source_connections WHERE id=?", String.class, connectionId))
        .isEqualTo("PENDING");
  }

  @Test
  @DisplayName("외부 트랜잭션에서 공개 writer를 호출하면 외부 등록 전 거부한다")
  void refusesAmbientTransaction() {
    assertThatThrownBy(() ->
            transactions.executeWithoutResult(tx ->
                writer.configureFigma(
                    workspaceId, connectionId, new ConfigureFigmaCommand("team", List.of("f1")))))
        .isInstanceOf(IllegalTransactionStateException.class);
    verifyNoInteractions(client);
  }

  private MockHttpServletRequestBuilder request(Object id) {
    return post("/api/source-connections/{id}/figma/configure", id)
        .header("API-Version", "1")
        .header("Authorization", "Bearer " + tokens.issueAccessToken(userId))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"team_id\":\"team\",\"file_keys\":[\"f1\"]}");
  }
}
