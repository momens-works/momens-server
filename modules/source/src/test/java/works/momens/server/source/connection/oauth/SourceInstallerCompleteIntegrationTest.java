package works.momens.server.source.connection.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import works.momens.server.common.api.BusinessException;
import works.momens.server.common.persistence.JpaAuditingConfig;
import works.momens.server.common.test.AbstractPostgresIntegrationTest;
import works.momens.server.source.CompleteInstallCommand;
import works.momens.server.source.CompletedInstall;
import works.momens.server.source.SourceErrorCode;
import works.momens.server.source.connection.SourceConnectionRepository;
import works.momens.server.source.connection.SourceCredentialRepository;
import works.momens.server.source.connection.oauth.ProviderDefinition.ClientAuthStyle;
import works.momens.server.source.connection.oauth.ProviderDefinition.IdentityMethod;
import works.momens.server.source.connection.oauth.ProviderDefinition.TokenBodyFormat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaAuditingConfig.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
/**
 * provider 승인 결과를 받아 source 연결과 자격 증명을 저장하는 흐름을 실제 PostgreSQL과 로컬 HTTP 서버로 검증합니다.
 *
 * <p>테스트에서는 provider 정의의 URL을 로컬 HTTP 서버 주소로 교체해 토큰 교환과 사용자 정보 조회를 검증합니다.
 */
class SourceInstallerCompleteIntegrationTest extends AbstractPostgresIntegrationTest {

  private static final String SECRET = "state-secret-that-is-long-enough-for-hs256";
  private static final String TOKEN_KEY =
      Base64.getEncoder()
          .encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
  private static final Instant NOW = Instant.parse("2026-08-21T00:00:00Z");

  @Autowired private SourceConnectionRepository connectionRepository;
  @Autowired private SourceCredentialRepository credentialRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private PlatformTransactionManager transactionManager;

  private HttpServer server;
  private final AtomicReference<String> tokenBody = new AtomicReference<>();
  private final AtomicInteger tokenStatus = new AtomicInteger(200);
  private UUID workspaceId;

  @BeforeEach
  void setUp() throws IOException {
    tokenBody.set(
        "{\"access_token\":\"tok-1\",\"refresh_token\":\"ref-1\",\"token_type\":\"bearer\"}");
    tokenStatus.set(200);
    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext(
        "/token", exchange -> respond(exchange, tokenStatus.get(), tokenBody.get()));
    server.createContext(
        "/identity",
        exchange -> respond(exchange, 200, "{\"login\":\"jsshin\",\"name\":\"신진수\",\"id\":7}"));
    server.start();
    workspaceId = insertWorkspace();
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
    jdbcTemplate.update("DELETE FROM source_credentials");
    jdbcTemplate.update("DELETE FROM source_connections");
    jdbcTemplate.update("DELETE FROM workspaces WHERE id = ?", workspaceId);
    jdbcTemplate.update("DELETE FROM users WHERE email LIKE 'install-test-%'");
  }

  private static void respond(HttpExchange exchange, int status, String body) throws IOException {
    byte[] payload = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, payload.length);
    exchange.getResponseBody().write(payload);
    exchange.close();
  }

  private SourceOAuthProperties properties() {
    return new SourceOAuthProperties(
        "https://api.momens.works/api/source-connections/oauth/callback",
        "https://app.momens.works/settings/sources",
        SECRET,
        TOKEN_KEY,
        Duration.ofMinutes(10),
        Map.of("github", new SourceOAuthProperties.ProviderCredentials("cid", "secret")));
  }

  private SourceInstallerImpl installer() {
    SourceOAuthProperties properties = properties();
    String base = "http://localhost:" + server.getAddress().getPort();
    ProviderDefinition definition =
        new ProviderDefinition(
            OAuthProviderRegistry.GITHUB,
            base + "/authorize",
            base + "/token",
            List.of("read:user"),
            Map.of(),
            TokenBodyFormat.FORM,
            ClientAuthStyle.REQUEST_BODY,
            base + "/identity",
            IdentityMethod.GET,
            ProviderIdentities::github);
    OAuthProviderRegistry registry =
        new OAuthProviderRegistry(properties) {
          @Override
          Optional<OAuthProvider> find(String sourceType) {
            return OAuthProviderRegistry.GITHUB.equals(OAuthStateSigner.normalize(sourceType))
                ? Optional.of(
                    new OAuthProvider(definition, "cid", "secret", properties.redirectUri()))
                : Optional.empty();
          }
        };
    return new SourceInstallerImpl(
        registry,
        signer(),
        new ProviderOAuthClient(RestClient.builder().build()),
        new TokenEncryptor(TOKEN_KEY),
        connectionRepository,
        credentialRepository,
        properties,
        new TransactionTemplate(transactionManager));
  }

  private OAuthStateSigner signer() {
    return new OAuthStateSigner(SECRET, Duration.ofMinutes(10), Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private String state(UUID userId) {
    return signer().sign(new OAuthState(workspaceId, userId, OAuthProviderRegistry.GITHUB));
  }

  @Test
  @DisplayName("승인이 완료되면 연결 정보와 암호화된 토큰을 저장한다")
  void storesConnectionAndEncryptedTokenWhenTheProviderApproves() {
    UUID userId = insertUser();

    CompletedInstall completed =
        installer().completeInstall(new CompleteInstallCommand("code-1", state(userId)));

    assertThat(completed.successRedirectUri())
        .isEqualTo("https://app.momens.works/settings/sources");
    assertThat(completed.connection().status()).isEqualTo("ACTIVE");
    assertThat(completed.connection().externalWorkspaceId()).isEqualTo("jsshin");
    assertThat(completed.connection().externalWorkspaceName()).isEqualTo("신진수");
    assertThat(completed.connection().connectedByUserId()).isEqualTo(userId);

    var credential = credentialRepository.findById(completed.connection().id()).orElseThrow();
    assertThat(new TokenEncryptor(TOKEN_KEY).decrypt(credential.getAccessTokenEnc()))
        .isEqualTo("tok-1");
    assertThat(new TokenEncryptor(TOKEN_KEY).decrypt(credential.getRefreshTokenEnc()))
        .isEqualTo("ref-1");
    assertThat(credential.getTokenType()).isEqualTo("bearer");
  }

  @Test
  @DisplayName("같은 외부 계정을 다시 승인하면 기존 연결을 갱신하고 토큰을 교체한다")
  void updatesTheSameConnectionWhenTheSameAccountApprovesAgain() {
    UUID firstUser = insertUser();
    UUID secondUser = insertUser();
    UUID firstId =
        installer()
            .completeInstall(new CompleteInstallCommand("code-1", state(firstUser)))
            .connection()
            .id();

    tokenBody.set("{\"access_token\":\"tok-2\",\"token_type\":\"bearer\"}");
    CompletedInstall second =
        installer().completeInstall(new CompleteInstallCommand("code-2", state(secondUser)));

    assertThat(second.connection().id()).isEqualTo(firstId);
    assertThat(second.connection().connectedByUserId()).isEqualTo(secondUser);
    assertThat(connectionRepository.findByWorkspaceIdOrderByCreatedAtDesc(workspaceId)).hasSize(1);
    assertThat(
            new TokenEncryptor(TOKEN_KEY)
                .decrypt(credentialRepository.findById(firstId).orElseThrow().getAccessTokenEnc()))
        .isEqualTo("tok-2");
  }

  @Test
  @DisplayName("같은 외부 계정의 연결이 두 건이어도 재승인이 실패하지 않는다")
  void updatesEveryDuplicateConnectionWhenTheSameAccountApprovesAgain() {
    UUID firstUser = insertUser();
    installer().completeInstall(new CompleteInstallCommand("code-1", state(firstUser)));
    jdbcTemplate.update(
        "INSERT INTO source_connections (id, workspace_id, source_type, status,"
            + " external_workspace_id, created_at, updated_at)"
            + " VALUES (?, ?, 'GITHUB', 'ACTIVE', 'jsshin', NOW(), NOW())",
        UUID.randomUUID(),
        workspaceId);

    tokenBody.set("{\"access_token\":\"tok-3\",\"token_type\":\"bearer\"}");
    CompletedInstall again =
        installer().completeInstall(new CompleteInstallCommand("code-3", state(insertUser())));

    assertThat(connectionRepository.findByWorkspaceIdOrderByCreatedAtDesc(workspaceId)).hasSize(2);
    assertThat(again.connection().status()).isEqualTo("ACTIVE");
  }

  @Test
  @DisplayName("승인 코드나 state가 없으면 거부한다")
  void rejectsRequestWithoutCodeOrState() {
    assertThatThrownBy(
            () -> installer().completeInstall(new CompleteInstallCommand("", state(insertUser()))))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorCode", SourceErrorCode.SOURCE_OAUTH_INVALID_REQUEST);
  }

  @Test
  @DisplayName("검증할 수 없는 state는 거부한다")
  void rejectsStateThatDoesNotVerify() {
    assertThatThrownBy(
            () -> installer().completeInstall(new CompleteInstallCommand("code-1", "not-a-state")))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorCode", SourceErrorCode.SOURCE_OAUTH_INVALID_STATE);
  }

  @Test
  @DisplayName("provider가 토큰 교환을 거부하면 502로 응답한다")
  void reportsBadGatewayWhenTheProviderRejectsTheExchange() {
    tokenStatus.set(500);

    assertThatThrownBy(
            () ->
                installer()
                    .completeInstall(
                        new CompleteInstallCommand("code-1", state(UUID.randomUUID()))))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorCode", SourceErrorCode.SOURCE_OAUTH_EXCHANGE_FAILED);
  }

  @Test
  @DisplayName("provider가 access token을 반환하지 않으면 502로 응답한다")
  void reportsBadGatewayWhenTheProviderReturnsNoAccessToken() {
    tokenBody.set("{\"token_type\":\"bearer\"}");

    assertThatThrownBy(
            () ->
                installer()
                    .completeInstall(
                        new CompleteInstallCommand("code-1", state(UUID.randomUUID()))))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorCode", SourceErrorCode.SOURCE_OAUTH_EXCHANGE_FAILED);
  }

  private UUID insertUser() {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO users (id, email, name, created_at, updated_at)"
            + " VALUES (?, ?, ?, NOW(), NOW())",
        id,
        "install-test-" + id + "@momens.works",
        "신진수");
    return id;
  }

  private UUID insertWorkspace() {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO workspaces (id, name, slug, created_at, updated_at)"
            + " VALUES (?, ?, ?, NOW(), NOW())",
        id,
        "모먼스",
        "ws-" + id.toString().substring(0, 8));
    return id;
  }
}
