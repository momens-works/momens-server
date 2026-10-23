package works.momens.server.source.connection.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.sun.net.httpserver.HttpServer;
import com.zaxxer.hikari.HikariDataSource;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import works.momens.server.common.persistence.JpaAuditingConfig;
import works.momens.server.common.test.AbstractPostgresIntegrationTest;
import works.momens.server.source.connection.SourceCredentialRepository;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaAuditingConfig.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("Figma token 갱신 PostgreSQL·provider 통합 테스트")
class FigmaTokenRefresherIntegrationTest extends AbstractPostgresIntegrationTest {

  private static final String TOKEN_KEY =
      Base64.getEncoder()
          .encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
  private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MICROS);

  @Autowired SourceCredentialRepository credentials;
  @Autowired PlatformTransactionManager manager;
  @Autowired JdbcTemplate jdbc;
  @Autowired DataSource dataSource;

  private final TokenEncryptor encryptor = new TokenEncryptor(TOKEN_KEY);
  private final AtomicInteger status = new AtomicInteger(200);
  private final AtomicInteger activeConnectionsDuringCall = new AtomicInteger(-1);
  private final List<String> requests = new CopyOnWriteArrayList<>();
  private final List<UUID> workspaces = new CopyOnWriteArrayList<>();
  private HttpServer server;

  @BeforeEach
  void setUp() throws Exception {
    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext(
        "/v1/oauth/refresh",
        exchange -> {
          requests.add(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          try {
            activeConnectionsDuringCall.set(
                dataSource
                    .unwrap(HikariDataSource.class)
                    .getHikariPoolMXBean()
                    .getActiveConnections());
          } catch (SQLException e) {
            throw new IllegalStateException(e);
          }
          byte[] payload =
              "{\"access_token\":\"new-access\",\"token_type\":\"bearer\",\"expires_in\":7776000}"
                  .getBytes(StandardCharsets.UTF_8);
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
    workspaces.forEach(
        id -> {
          jdbc.update("DELETE FROM source_connections WHERE workspace_id=?", id);
          jdbc.update("DELETE FROM workspaces WHERE id=?", id);
        });
  }

  private FigmaTokenRefresher refresher(String clientId) {
    return refresher(clientId, credentials);
  }

  private FigmaTokenRefresher refresher(String clientId, SourceCredentialRepository repository) {
    var properties =
        new SourceOAuthProperties(
            "https://api.example/callback",
            "https://app.example",
            "long-enough-state-secret-for-hs256-test",
            TOKEN_KEY,
            Duration.ofMinutes(10),
            Map.of("figma", new SourceOAuthProperties.ProviderCredentials(clientId, "secret")));
    var factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Duration.ofSeconds(1));
    factory.setReadTimeout(Duration.ofSeconds(5));
    return new FigmaTokenRefresher(
        new OAuthProviderRegistry(properties),
        repository,
        encryptor,
        new FigmaTokenRefreshClient(
            RestClient.builder()
                .baseUrl("http://localhost:" + server.getAddress().getPort())
                .requestFactory(factory)
                .build()),
        new FigmaTokenRefreshProperties(Duration.ofHours(1), Duration.ofDays(7)),
        manager);
  }

  private FigmaTokenRefresher refresher() {
    return refresher("cid");
  }

  @Test
  @DisplayName("만료가 임박한 사용자의 모든 연결에 가장 최근 refresh token으로 받은 token을 저장한다")
  void refreshesEveryCredentialOfTheUser() {
    UUID first = workspace();
    UUID second = workspace();
    UUID due =
        credential(
            first, "ACTIVE", "figma-user", "old-a", "ref-new", days(3), NOW.minusSeconds(60));
    UUID disabledElsewhere =
        credential(
            second, "DISABLED", "figma-user", "old-b", "ref-old", days(30), NOW.minusSeconds(600));
    UUID otherUser = credential(first, "ACTIVE", "other-user", "other", "ref-other", days(30), NOW);

    refresher().refreshDue();

    assertThat(requests).containsExactly("refresh_token=ref-new");
    for (UUID id : List.of(due, disabledElsewhere)) {
      var row = credentials.findById(id).orElseThrow();
      assertThat(encryptor.decrypt(row.getAccessTokenEnc())).isEqualTo("new-access");
      assertThat(encryptor.decrypt(row.getRefreshTokenEnc())).isEqualTo("ref-new");
      assertThat(row.getTokenType()).isEqualTo("bearer");
      assertThat(row.getExpiresAt())
          .isCloseTo(Instant.now().plus(90, ChronoUnit.DAYS), within(1, ChronoUnit.MINUTES));
    }
    assertThat(encryptor.decrypt(credentials.findById(otherUser).orElseThrow().getAccessTokenEnc()))
        .isEqualTo("other");
  }

  @Test
  @DisplayName("이미 만료됐거나 만료까지 기간이 남은 token은 갱신하지 않는다")
  void ignoresExpiredAndDistantTokens() {
    UUID workspace = workspace();
    credential(workspace, "ACTIVE", "expired-user", "a", "r", NOW.minusSeconds(60), NOW);
    credential(workspace, "ACTIVE", "distant-user", "b", "r", days(8), NOW);

    refresher().refreshDue();

    assertThat(requests).isEmpty();
  }

  @Test
  @DisplayName("Figma가 갱신을 거부하면 저장된 token을 유지하고 비밀 값 없이 경고를 남긴다")
  void keepsCredentialWhenFigmaRejects(CapturedOutput output) {
    UUID id = credential(workspace(), "ACTIVE", "figma-user", "old", "private-ref", days(3), NOW);
    status.set(401);

    refresher().refreshDue();

    var row = credentials.findById(id).orElseThrow();
    assertThat(encryptor.decrypt(row.getAccessTokenEnc())).isEqualTo("old");
    assertThat(row.getExpiresAt()).isEqualTo(days(3));
    assertThat(output.getOut())
        .contains(
            "Figma token refresh failed",
            "figmaUserId=figma-user",
            "reauthRequired=true",
            "status=401")
        .doesNotContain("private-ref", "new-access");
  }

  @Test
  @DisplayName("일시적 실패는 저장된 token을 유지한다")
  void keepsCredentialOnTransientFailure(CapturedOutput output) {
    UUID id = credential(workspace(), "ACTIVE", "figma-user", "old", "ref", days(3), NOW);
    status.set(503);

    refresher().refreshDue();

    assertThat(encryptor.decrypt(credentials.findById(id).orElseThrow().getAccessTokenEnc()))
        .isEqualTo("old");
    assertThat(output.getOut()).contains("reauthRequired=false", "status=503");
  }

  @Test
  @DisplayName("저장에 실패하면 다음 주기에 다시 선택되어 같은 refresh token으로 회복한다")
  void retriesUnsavedRefreshOnNextRun(CapturedOutput output) {
    UUID id = credential(workspace(), "ACTIVE", "figma-user", "old", "ref", days(3), NOW);
    jdbc.execute(
        """
        CREATE FUNCTION reject_figma_refresh() RETURNS trigger LANGUAGE plpgsql AS $$
        BEGIN RAISE EXCEPTION 'private-db-error'; RETURN NEW; END $$
        """);
    jdbc.execute(
        """
        CREATE TRIGGER reject_figma_refresh BEFORE UPDATE ON source_credentials
        FOR EACH ROW EXECUTE FUNCTION reject_figma_refresh()
        """);
    try {
      refresher().refreshDue();
    } finally {
      jdbc.execute("DROP TRIGGER reject_figma_refresh ON source_credentials");
      jdbc.execute("DROP FUNCTION reject_figma_refresh()");
    }
    assertThat(encryptor.decrypt(credentials.findById(id).orElseThrow().getAccessTokenEnc()))
        .isEqualTo("old");
    assertThat(output.getOut())
        .contains(
            "Figma token refresh save failed figmaUserId=figma-user failureType=JpaSystemException");

    refresher().refreshDue();

    assertThat(requests).containsExactly("refresh_token=ref", "refresh_token=ref");
    assertThat(encryptor.decrypt(credentials.findById(id).orElseThrow().getAccessTokenEnc()))
        .isEqualTo("new-access");
  }

  @Test
  @DisplayName("Figma를 호출하는 동안 DB 연결을 잡지 않는다")
  void releasesDatabaseConnectionDuringFigmaCall() {
    credential(workspace(), "ACTIVE", "figma-user", "old", "ref", days(3), NOW);

    refresher().refreshDue();

    assertThat(requests).hasSize(1);
    assertThat(activeConnectionsDuringCall).hasValue(0);
  }

  @Test
  @DisplayName("대상 조회 뒤 다른 인스턴스가 이미 갱신한 사용자는 건너뛴다")
  void skipsUserAlreadyRefreshedElsewhere() {
    UUID id = credential(workspace(), "ACTIVE", "figma-user", "old", "ref", days(3), NOW);
    SourceCredentialRepository interleaved =
        mock(SourceCredentialRepository.class, delegatesTo(credentials));
    doAnswer(
            invocation -> {
              var due =
                  credentials.findFigmaUsersExpiringBetween(
                      invocation.getArgument(0), invocation.getArgument(1));
              jdbc.update(
                  "UPDATE source_credentials SET expires_at = ? WHERE connection_id = ?",
                  Timestamp.from(days(90)),
                  id);
              return due;
            })
        .when(interleaved)
        .findFigmaUsersExpiringBetween(any(), any());

    refresher("cid", interleaved).refreshDue();

    assertThat(requests).isEmpty();
    assertThat(encryptor.decrypt(credentials.findById(id).orElseThrow().getAccessTokenEnc()))
        .isEqualTo("old");
  }

  @Test
  @DisplayName("Figma OAuth 자격 증명이 설정되지 않으면 갱신하지 않는다")
  void skipsWhenFigmaIsNotConfigured() {
    credential(workspace(), "ACTIVE", "figma-user", "old", "ref", days(3), NOW);

    refresher("").refreshDue();

    assertThat(requests).isEmpty();
  }

  private static Instant days(long days) {
    return NOW.plus(days, ChronoUnit.DAYS);
  }

  private UUID workspace() {
    UUID id = UUID.randomUUID();
    jdbc.update("INSERT INTO workspaces(id,name,slug) VALUES (?, 'figma', ?)", id, "figma-" + id);
    workspaces.add(id);
    return id;
  }

  private UUID credential(
      UUID workspaceId,
      String connectionStatus,
      String figmaUserId,
      String accessToken,
      String refreshToken,
      Instant expiresAt,
      Instant updatedAt) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO source_connections(id,workspace_id,source_type,status,external_workspace_id)"
            + " VALUES (?,?,'FIGMA',?,?)",
        id,
        workspaceId,
        connectionStatus,
        figmaUserId);
    jdbc.update(
        "INSERT INTO source_credentials(connection_id,access_token_enc,refresh_token_enc,expires_at,updated_at)"
            + " VALUES (?,?,?,?,?)",
        id,
        encryptor.encrypt(accessToken),
        encryptor.encrypt(refreshToken),
        Timestamp.from(expiresAt),
        Timestamp.from(updatedAt));
    return id;
  }
}
