package works.momens.server.mcp.oauth.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsent;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import works.momens.server.common.test.AbstractPostgresIntegrationTest;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = McpUnusedClientCleanupIntegrationTest.TestApplication.class)
@Import({
  McpOAuthPersistenceConfig.class,
  McpUnusedClientRepository.class,
  McpUnusedClientCleanupService.class
})
@DisplayName("MCP 미사용 client 정리 통합 테스트")
class McpUnusedClientCleanupIntegrationTest extends AbstractPostgresIntegrationTest {
  // Older than other test fixtures sharing the singleton database.
  private static final Instant NOW = Instant.parse("2000-02-01T00:00:00Z");
  @Autowired RegisteredClientRepository clients;
  @Autowired OAuth2AuthorizationService authorizations;
  @Autowired OAuth2AuthorizationConsentService consents;
  @Autowired McpUnusedClientRepository repository;
  @Autowired McpUnusedClientCleanupService cleanup;
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager transactionManager;

  @Test
  @DisplayName("24시간 이상 지난 미사용 등록은 삭제하고 24시간 미만 등록은 보존한다")
  void deletesOnlyOldUnusedRegistrations() {
    RegisteredClient old = client(25);
    RegisteredClient boundary = client(24);
    RegisteredClient recent = client(23);

    assertThat(cleanup.deleteUnusedClients()).isEqualTo(2);
    assertThat(clients.findById(old.getId())).isNull();
    assertThat(clients.findById(boundary.getId())).isNull();
    assertThat(clients.findById(recent.getId())).isNotNull();
    assertThat(cleanup.deleteUnusedClients()).isZero();
  }

  @Test
  @DisplayName("인가 진행·만료 토큰·동의·폐기 grant 중 하나라도 있으면 등록과 토큰을 보존한다")
  void preservesAnyAuthorizationConsentOrGrantHistory() {
    RegisteredClient pending = client(25);
    authorizations.save(authorization(pending));
    RegisteredClient tokenClient = client(25);
    OAuth2Authorization tokens =
        OAuth2Authorization.from(authorization(tokenClient))
            .accessToken(
                new OAuth2AccessToken(
                    OAuth2AccessToken.TokenType.BEARER,
                    "expired-access",
                    NOW.minusSeconds(7200),
                    NOW.minusSeconds(3600)))
            .refreshToken(
                new OAuth2RefreshToken(
                    "expired-refresh", NOW.minusSeconds(7200), NOW.minusSeconds(3600)))
            .build();
    authorizations.save(tokens);
    OAuth2Authorization persistedTokens = authorizations.findById(tokens.getId());
    RegisteredClient consentClient = client(25);
    consents.save(
        OAuth2AuthorizationConsent.withId(consentClient.getId(), "user")
            .scope("mcp:tasks:read")
            .build());
    RegisteredClient grantClient = client(25);
    UUID user = UUID.randomUUID();
    UUID workspace = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO users (id, email, name) VALUES (?, ?, 'cleanup test')",
        user,
        user + "@example.com");
    jdbc.update(
        "INSERT INTO workspaces (id, name, slug) VALUES (?, 'cleanup test', ?)",
        workspace,
        workspace.toString());
    jdbc.update(
        """
        INSERT INTO mcp_grants (user_id, client_id, workspace_id, scopes, approved_at, revoked_at)
        VALUES (?, ?, ?, ARRAY['mcp:tasks:read'], ?, ?)
        """,
        user,
        grantClient.getClientId(),
        workspace,
        Timestamp.from(NOW),
        Timestamp.from(NOW));

    assertThat(cleanup.deleteUnusedClients()).isZero();
    for (RegisteredClient client :
        new RegisteredClient[] {pending, tokenClient, consentClient, grantClient}) {
      assertThat(clients.findById(client.getId())).isNotNull();
    }
    assertThat(authorizations.findById(tokens.getId())).isEqualTo(persistedTokens);
    assertThat(consents.findById(consentClient.getId(), "user")).isNotNull();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM mcp_grants WHERE client_id = ?",
                Long.class,
                grantClient.getClientId()))
        .isEqualTo(1);
  }

  @Test
  @DisplayName("한 주기에 최대 100건을 정리하고 다음 주기에 나머지를 처리한다")
  void boundsEachBatch() {
    for (int i = 0; i < 101; i++) {
      client(25);
    }
    assertThat(cleanup.deleteUnusedClients()).isEqualTo(100);
    assertThat(cleanup.deleteUnusedClients()).isEqualTo(1);
  }

  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  @DisplayName("최초 인가가 진행 중이면 정리가 건너뛰고 commit 이후에도 보존한다")
  void skipsConcurrentFirstAuthorization() throws Exception {
    RegisteredClient client = client(25);
    CountDownLatch saved = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    try (var executor = Executors.newSingleThreadExecutor()) {
      var save =
          executor.submit(() ->
              new TransactionTemplate(transactionManager)
                  .executeWithoutResult(status -> {
                    authorizations.save(authorization(client));
                    saved.countDown();
                    waitFor(release);
                  }));
      try {
        assertThat(saved.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(cleanup.deleteUnusedClients()).isZero();
      } finally {
        release.countDown();
      }
      save.get(5, TimeUnit.SECONDS);
      assertThat(cleanup.deleteUnusedClients()).isZero();
      assertThat(clients.findById(client.getId())).isNotNull();
    } finally {
      removeFixture(client);
    }
  }

  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  @DisplayName("정리가 먼저 잠그면 다른 정리는 건너뛰고 최초 인가는 삭제 후 invalid_client로 끝난다")
  void deletionBeforeAuthorizationDoesNotLeaveOrphan() throws Exception {
    RegisteredClient client = client(25);
    CountDownLatch deleted = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger authorizingPid = new AtomicInteger();
    try (var executor = Executors.newFixedThreadPool(2)) {
      var deletion =
          executor.submit(() ->
              new TransactionTemplate(transactionManager)
                  .executeWithoutResult(status -> {
                    assertThat(repository.lockUnusedBefore(NOW, 100)).contains(client.getId());
                    assertThat(repository.deleteIfUnused(client.getId())).isEqualTo(1);
                    deleted.countDown();
                    waitFor(release);
                  }));
      try {
        assertThat(deleted.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(cleanup.deleteUnusedClients()).isZero();
        var save =
            executor.submit(() ->
                assertThatThrownBy(() ->
                        new TransactionTemplate(transactionManager)
                            .executeWithoutResult(status -> {
                              authorizingPid.set(
                                  jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                              authorizations.save(authorization(client));
                            }))
                    .isInstanceOfSatisfying(
                        OAuth2AuthenticationException.class,
                        exception ->
                            assertThat(exception.getError().getErrorCode())
                                .isEqualTo("invalid_client")));
        await()
            .atMost(Duration.ofSeconds(5))
            .untilAsserted(() ->
                assertThat(
                        jdbc.queryForObject(
                            "SELECT count(*) FROM pg_locks WHERE pid = ? AND NOT granted",
                            Long.class,
                            authorizingPid.get()))
                    .isPositive());
        release.countDown();
        deletion.get(5, TimeUnit.SECONDS);
        save.get(5, TimeUnit.SECONDS);
        assertThat(clients.findById(client.getId())).isNull();
        assertThat(
                jdbc.queryForObject(
                    "SELECT count(*) FROM oauth2_authorization WHERE registered_client_id = ?",
                    Long.class,
                    client.getId()))
            .isZero();
      } finally {
        release.countDown();
      }
    } finally {
      removeFixture(client);
    }
  }

  @Test
  @DisplayName("정리 SQL이 등록 시각과 사용 이력 조회 인덱스를 사용할 수 있다")
  void cleanupQueriesCanUseIndexes() {
    RegisteredClient client = client(25);
    jdbc.execute("SET LOCAL enable_seqscan = off");
    String candidates =
        String.join(
            "\n",
            jdbc.queryForList(
                "EXPLAIN " + McpUnusedClientRepository.LOCK_UNUSED_SQL,
                String.class,
                Timestamp.from(NOW.minus(Duration.ofHours(24))),
                100));
    assertThat(candidates)
        .contains(
            "idx_mcp_client_issued_at",
            "idx_mcp_authorization_registered_client",
            "idx_mcp_grants_client");
    String deletion =
        String.join(
            "\n",
            jdbc.queryForList(
                "EXPLAIN " + McpUnusedClientRepository.DELETE_UNUSED_SQL,
                String.class,
                client.getId()));
    assertThat(deletion)
        .contains("idx_mcp_authorization_registered_client", "idx_mcp_grants_client");
  }

  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  @DisplayName("후보 조회 스냅샷 이후 커밋된 인가를 삭제 직전에 다시 확인한다")
  void rechecksAuthorizationCommittedAfterCandidateSnapshot() throws Exception {
    RegisteredClient client = client(25);
    AtomicInteger cleanupPid = new AtomicInteger();
    // Pause cutoff evaluation after the statement snapshot exists but before client row locking.
    // Only the cutoff parameter expression changes; predicates and row locking use production SQL.
    String pausedQuery =
        McpUnusedClientRepository.LOCK_UNUSED_SQL.replace(
            "<= ?", "<= (SELECT ?::timestamp FROM pg_advisory_xact_lock(?))");
    try (var barrier = jdbc.getDataSource().getConnection();
        var executor = Executors.newSingleThreadExecutor()) {
      long key;
      try (var statement = barrier.createStatement();
          var result = statement.executeQuery("SELECT pg_backend_pid()")) {
        assertThat(result.next()).isTrue();
        key = result.getLong(1);
      }
      try (var lock = barrier.prepareStatement("SELECT pg_advisory_lock(?)")) {
        lock.setLong(1, key);
        lock.execute();
      }
      var deletion =
          executor.submit(() ->
              new TransactionTemplate(transactionManager)
                  .execute(status -> {
                    cleanupPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                    assertThat(
                            jdbc.queryForList(
                                pausedQuery,
                                String.class,
                                Timestamp.from(NOW.minus(Duration.ofHours(24))),
                                key,
                                100))
                        .contains(client.getId());
                    return repository.deleteIfUnused(client.getId());
                  }));
      try {
        await()
            .atMost(Duration.ofSeconds(5))
            .untilAsserted(() ->
                assertThat(
                        jdbc.queryForObject(
                            "SELECT count(*) FROM pg_locks WHERE pid = ? AND locktype = 'advisory' AND NOT granted",
                            Long.class,
                            cleanupPid.get()))
                    .isEqualTo(1));
        new TransactionTemplate(transactionManager)
            .executeWithoutResult(status -> authorizations.save(authorization(client)));
      } finally {
        try (var unlock = barrier.prepareStatement("SELECT pg_advisory_unlock(?)")) {
          unlock.setLong(1, key);
          unlock.execute();
        }
      }
      assertThat(deletion.get(5, TimeUnit.SECONDS)).isZero();
      assertThat(clients.findById(client.getId())).isNotNull();
    } finally {
      removeFixture(client);
    }
  }

  private RegisteredClient client(int ageHours) {
    RegisteredClient client =
        RegisteredClient.withId(UUID.randomUUID().toString())
            .clientId(UUID.randomUUID().toString())
            .clientIdIssuedAt(NOW.minus(Duration.ofHours(ageHours)))
            .clientName("cleanup test")
            .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("http://localhost/callback")
            .scope("mcp:tasks:read")
            .build();
    clients.save(client);
    return client;
  }

  private OAuth2Authorization authorization(RegisteredClient client) {
    return OAuth2Authorization.withRegisteredClient(client)
        .principalName("pending")
        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
        .build();
  }

  private void removeFixture(RegisteredClient client) {
    jdbc.update("DELETE FROM oauth2_authorization WHERE registered_client_id = ?", client.getId());
    jdbc.update("DELETE FROM oauth2_registered_client WHERE id = ?", client.getId());
  }

  private static void waitFor(CountDownLatch latch) {
    try {
      assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(exception);
    }
  }

  @SpringBootConfiguration
  @EnableAutoConfiguration
  static class TestApplication {
    @Bean
    Clock clock() {
      return Clock.fixed(NOW, ZoneOffset.UTC);
    }
  }
}
