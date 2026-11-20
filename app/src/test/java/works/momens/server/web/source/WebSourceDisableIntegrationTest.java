package works.momens.server.web.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.CannotCreateTransactionException;
import works.momens.server.auth.AccessTokenTestFactory;
import works.momens.server.common.test.AbstractPostgresIntegrationTest;
import works.momens.server.source.connection.SourceCredentialRepository;
import works.momens.server.user.UserService;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("source 연결 비활성화 HTTP 통합 테스트")
class WebSourceDisableIntegrationTest extends AbstractPostgresIntegrationTest {

  @Autowired private MockMvc mvc;
  @Autowired private AccessTokenTestFactory accessTokens;
  @Autowired private UserService users;
  @Autowired private JdbcTemplate jdbc;
  @MockitoSpyBean private SourceCredentialRepository credentials;

  @Test
  @DisplayName("정리용 트랜잭션 시작이 실패해도 성공 응답과 커밋된 비활성 상태를 유지한다")
  void cleanupTransactionFailurePreservesSuccessAndCommittedState(CapturedOutput output)
      throws Exception {
    Fixture fixture = fixture("owner");
    jdbc.update(
        "UPDATE source_connections SET source_type = 'FIGMA',"
            + " metadata = '{\"webhook_id\":\"wh-tx-failure\"}'::jsonb WHERE id = ?",
        fixture.connectionId());
    // 실제 DB 장애가 아니라 정리 단계의 credential 조회에 transaction 시작 실패를 주입합니다.
    doThrow(new CannotCreateTransactionException("private-transaction-details"))
        .when(credentials)
        .findById(fixture.connectionId());

    mvc.perform(authorized(fixture.connectionId(), fixture.userId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.message").value("disabled"));

    verify(credentials).findById(fixture.connectionId());
    Map<String, Object> disabled = row(fixture.connectionId());
    assertThat(disabled.get("status")).isEqualTo("DISABLED");
    assertThat(disabled.get("disabled_at")).isNotNull();
    assertThat(output)
        .contains(
            "WARN",
            "failureType=CannotCreateTransactionException",
            fixture.connectionId().toString())
        .doesNotContain("private-transaction-details");
  }

  @ParameterizedTest
  @ValueSource(strings = {"admin", "owner"})
  @DisplayName("admin과 owner는 연결을 비활성화하고 반복 요청은 Standard 오류를 받는다")
  void authorizedDisablePreservesSuccessAndRepeatUsesStandardError(String role) throws Exception {
    Fixture fixture = fixture(role);
    mvc.perform(authorized(fixture.connectionId(), fixture.userId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.message").value("disabled"));
    Map<String, Object> disabled = row(fixture.connectionId());
    assertThat(disabled.get("status")).isEqualTo("DISABLED");
    assertThat(disabled.get("disabled_at")).isNotNull();
    mvc.perform(authorized(fixture.connectionId(), fixture.userId()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("SOURCE_CONNECTION_ALREADY_DISABLED"));
    assertThat(row(fixture.connectionId())).isEqualTo(disabled);
  }

  @ParameterizedTest
  @ValueSource(strings = {"member", "outsider"})
  @DisplayName("member와 비회원의 비활성화 요청은 상태 변경 없이 거부한다")
  void insufficientRoleDoesNotChangeConnection(String role) throws Exception {
    Fixture fixture = fixture(role);
    Map<String, Object> before = row(fixture.connectionId());
    mvc.perform(authorized(fixture.connectionId(), fixture.userId()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("AUTH_FORBIDDEN"))
        .andExpect(jsonPath("$.error.details.required_role").value("admin"));
    assertThat(row(fixture.connectionId())).isEqualTo(before);
  }

  @Test
  @DisplayName("인증이 없으면 상태 변경 없이 비활성화를 거부한다")
  void missingAuthenticationDoesNotChangeConnection() throws Exception {
    Fixture fixture = fixture("owner");
    Map<String, Object> before = row(fixture.connectionId());
    mvc.perform(post("/api/source-connections/{id}/disable", fixture.connectionId()))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error.code").value("AUTH_UNAUTHORIZED"));
    assertThat(row(fixture.connectionId())).isEqualTo(before);
  }

  @Test
  @DisplayName("잘못된 UUID와 없는 연결은 Standard 오류를 반환한다")
  void invalidAndMissingIdsUseStandardErrors() throws Exception {
    Fixture fixture = fixture("owner");
    mvc.perform(authorized("invalid", fixture.userId()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("COMMON_BAD_REQUEST"));
    UUID missingId = UUID.randomUUID();
    mvc.perform(authorized(missingId, fixture.userId()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("SOURCE_CONNECTION_NOT_FOUND"))
        .andExpect(jsonPath("$.error.details.source_connection_id").value(missingId.toString()))
        .andExpect(jsonPath("$.error.details.length()").value(1));
  }

  @Test
  @DisplayName("Figma 자격 증명이 없어도 연결 비활성화는 성공한다")
  void figmaMissingCredentialsStillDisables() throws Exception {
    Fixture fixture = fixture("owner");
    jdbc.update(
        "UPDATE source_connections SET source_type = 'FIGMA',"
            + " metadata = '{\"webhook_id\":\"wh-no-token\"}'::jsonb WHERE id = ?",
        fixture.connectionId());
    mvc.perform(authorized(fixture.connectionId(), fixture.userId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.message").value("disabled"));
    assertThat(row(fixture.connectionId()).get("status")).isEqualTo("DISABLED");
  }

  private MockHttpServletRequestBuilder authorized(Object id, UUID userId) {
    return post("/api/source-connections/{id}/disable", id)
        .header("API-Version", "1")
        .header("Authorization", "Bearer " + accessTokens.issueAccessToken(userId));
  }

  private Fixture fixture(String role) {
    UUID userId =
        users.findOrCreate("disable-" + UUID.randomUUID() + "@example.com", "테스트", null).id();
    UUID workspaceId = UUID.randomUUID();
    UUID connectionId = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO workspaces (id, name, slug) VALUES (?, 'disable', ?)",
        workspaceId,
        "disable-" + workspaceId);
    if (!role.equals("outsider")) {
      jdbc.update(
          "INSERT INTO workspace_members (workspace_id, user_id, role) VALUES (?, ?, ?)",
          workspaceId,
          userId,
          role);
    }
    jdbc.update(
        "INSERT INTO source_connections (id, workspace_id, source_type, status)"
            + " VALUES (?, ?, 'GITHUB', 'ACTIVE')",
        connectionId,
        workspaceId);
    return new Fixture(userId, connectionId);
  }

  private Map<String, Object> row(UUID id) {
    return jdbc.queryForMap("SELECT * FROM source_connections WHERE id = ?", id);
  }

  private record Fixture(UUID userId, UUID connectionId) {}
}
