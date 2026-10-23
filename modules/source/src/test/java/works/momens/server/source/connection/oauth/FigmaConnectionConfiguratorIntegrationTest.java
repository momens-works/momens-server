package works.momens.server.source.connection.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;
import works.momens.server.common.test.AbstractPostgresIntegrationTest;
import works.momens.server.source.CompleteInstallCommand;
import works.momens.server.source.ConfigureFigmaCommand;
import works.momens.server.source.SourceConnectionDetail;
import works.momens.server.source.SourceErrorCode;
import works.momens.server.source.connection.SourceConnectionRepository;
import works.momens.server.source.connection.SourceCredentialRepository;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("Figma 설정 PostgreSQL·provider 통합 테스트")
class FigmaConnectionConfiguratorIntegrationTest extends AbstractPostgresIntegrationTest {
  @Autowired SourceConnectionRepository connections;
  @Autowired SourceCredentialRepository credentials;
  @Autowired PlatformTransactionManager manager;
  @Autowired JdbcTemplate jdbc;
  private final TokenEncryptor encryptor =
      new TokenEncryptor(
          Base64.getEncoder()
              .encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8)));
  private final AtomicInteger creates = new AtomicInteger();
  private final AtomicInteger createStatus = new AtomicInteger(200);
  private final AtomicInteger deleteStatus = new AtomicInteger(200);
  private final AtomicReference<String> createBody = new AtomicReference<>();
  private final AtomicReference<Runnable> duringCreate = new AtomicReference<>();
  private final List<String> deletes = new CopyOnWriteArrayList<>();
  private final List<String> events = new CopyOnWriteArrayList<>();
  private final AtomicReference<String> requestBody = new AtomicReference<>();
  private final AtomicReference<String> authorization = new AtomicReference<>();
  private UUID workspaceId;
  private UUID id;
  private HttpServer server;
  private ExecutorService httpThreads;
  private FigmaConnectionConfigurator configurator;

  @BeforeEach
  void setUp() throws Exception {
    workspaceId = UUID.randomUUID();
    id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO workspaces(id,name,slug) VALUES (?, 'figma', ?)",
        workspaceId,
        "figma-" + workspaceId);
    jdbc.update(
        """
        INSERT INTO source_connections(id,workspace_id,source_type,status,metadata,captures_read_count)
        VALUES (?,?,'FIGMA','PENDING','{"other":"keep"}'::jsonb,17)
        """,
        id,
        workspaceId);
    jdbc.update(
        "INSERT INTO source_credentials(connection_id,access_token_enc) VALUES (?,?)",
        id,
        encryptor.encrypt("private-token"));
    httpThreads = Executors.newCachedThreadPool();
    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.setExecutor(httpThreads);
    server.createContext(
        "/v2/webhooks",
        exchange -> {
          boolean create = exchange.getRequestMethod().equals("POST");
          int number = create ? creates.incrementAndGet() : 0;
          String webhookId =
              create
                  ? "new-" + number
                  : exchange.getRequestURI().getPath().substring("/v2/webhooks/".length());
          events.add((create ? "create:" : "delete:") + webhookId);
          if (create) {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            Runnable action = duringCreate.getAndSet(null);
            if (action != null) {
              action.run();
            }
          } else {
            deletes.add(webhookId);
            if (webhookId.equals("old")) {
              events.add("state:" + row().get("status"));
            }
          }
          String body = create ? createBody.get() : "{}";
          if (body == null) {
            body = "{\"id\":\"" + webhookId + "\"}";
          }
          byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(
              create ? createStatus.get() : deleteStatus.get(), bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
    configurator =
        configurator(
            new FigmaWebhookProperties("https://worker.example/webhooks/figma", "private-passcode"),
            Duration.ofSeconds(5));
  }

  private FigmaConnectionConfigurator configurator(
      FigmaWebhookProperties properties, Duration timeout) {
    return configurator(properties, timeout, new TransactionTemplate(manager));
  }

  private FigmaConnectionConfigurator configurator(
      FigmaWebhookProperties properties, Duration timeout, TransactionTemplate transactions) {
    var factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Duration.ofSeconds(1));
    factory.setReadTimeout(timeout);
    var client =
        new FigmaWebhookClient(
            RestClient.builder()
                .baseUrl("http://localhost:" + server.getAddress().getPort())
                .requestFactory(factory)
                .build());
    return new FigmaConnectionConfigurator(
        connections,
        credentials,
        encryptor,
        client,
        new FigmaWebhookCleaner(credentials, encryptor, client),
        properties,
        transactions);
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
    httpThreads.shutdownNow();
    jdbc.update("DELETE FROM source_connections WHERE id=?", id);
    jdbc.update("DELETE FROM workspaces WHERE id=?", workspaceId);
  }

  @Test
  @DisplayName("최초 설정은 정규화된 파일 목록과 webhook을 저장하고 worker 컬럼을 보존한다")
  void activatesAndNormalizes() {
    var result =
        configurator.configure(
            workspaceId,
            id,
            new ConfigureFigmaCommand(" team-1 ", List.of(" f1 ", "", "f2", "f1", "F1")));
    assertThat(result.status()).isEqualTo("ACTIVE");
    assertThat(result.metadata())
        .containsEntry("team_id", "team-1")
        .containsEntry("webhook_id", "new-1")
        .containsEntry("other", "keep")
        .containsEntry("file_keys", List.of("f1", "f2", "F1"));
    assertThat(result.capturesReadCount()).isEqualTo(17);
    assertThat(authorization.get()).isEqualTo("Bearer private-token");
    var request = JsonMapper.builder().build().readTree(requestBody.get());
    assertThat(request.path("context").asString()).isEqualTo("team");
    assertThat(request.path("event_type").asString()).isEqualTo("FILE_COMMENT");
    assertThat(request.path("context_id").asString()).isEqualTo("team-1");
    assertThat(request.path("passcode").asString()).isEqualTo("private-passcode");
    assertThat(request.path("endpoint").asString())
        .isEqualTo("https://worker.example/webhooks/figma");
    assertThat(deletes).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"PENDING", "ACTIVE", "DISABLED", "ERROR", "REVOKED"})
  @DisplayName("각 상태에서 재설정하면 비활성화 시각을 지우고 commit 후 이전 webhook을 삭제한다")
  void reconfiguresAllStates(String state) {
    previous(state);
    configure();
    assertThat(row().get("disabled_at")).isNull();
    assertThat(events).containsExactly("create:new-1", "delete:old", "state:ACTIVE");
  }

  @ParameterizedTest
  @ValueSource(strings = {"PENDING", "ACTIVE"})
  @DisplayName("등록 실패는 기존 상태와 metadata를 바꾸거나 기존 webhook을 삭제하지 않는다")
  void registrationFailurePreservesConnection(String state, CapturedOutput output) {
    previous(state);
    var before = row();
    createStatus.set(403);
    createBody.set("provider-private-response");
    assertThatThrownBy(this::configure)
        .hasFieldOrPropertyWithValue("errorCode", SourceErrorCode.SOURCE_FIGMA_WEBHOOK_FAILED)
        .hasMessageNotContaining("provider-private-response");
    assertThat(row()).isEqualTo(before);
    assertThat(deletes).isEmpty();
    assertThat(output)
        .doesNotContain("private-token", "provider-private-response", "private-passcode");
  }

  @ParameterizedTest
  @ValueSource(strings = {"{}", "{\"id\":42}", "{\"id\":\" \"}", "invalid-json"})
  @DisplayName("성공 응답의 webhook ID가 유효하지 않으면 활성화하지 않는다")
  void rejectsMalformedResponse(String body) {
    createBody.set(body);
    assertThatThrownBy(this::configure)
        .hasFieldOrPropertyWithValue("errorCode", SourceErrorCode.SOURCE_FIGMA_WEBHOOK_FAILED);
    assertThat(row().get("status")).isEqualTo("PENDING");
  }

  @Test
  @DisplayName("이전 webhook 삭제 실패는 새 연결의 활성화를 취소하지 않는다")
  void cleanupFailureKeepsNewConnection(CapturedOutput output) {
    previous("ACTIVE");
    deleteStatus.set(500);
    configure();
    assertThat(row().get("status")).isEqualTo("ACTIVE");
    assertThat(output).contains("status=500").doesNotContain("private-token", "private-passcode");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "status='DISABLED', disabled_at=now()",
        "status='PENDING', connected_at=now()",
        "metadata='{}'::jsonb"
      })
  @DisplayName("외부 호출 중 lifecycle 변경은 덮어쓰지 않고 이번 요청의 webhook만 삭제한다")
  void detectsConcurrentLifecycleChange(String update) {
    previous("ACTIVE");
    duringCreate.set(
        () -> jdbc.update("UPDATE source_connections SET " + update + " WHERE id=?", id));
    assertThatThrownBy(this::configure)
        .hasFieldOrPropertyWithValue("errorCode", SourceErrorCode.SOURCE_CONNECTION_CHANGED);
    assertThat(deletes).containsExactly("new-1");
  }

  @Test
  @DisplayName("외부 호출 중 credential 교체도 충돌로 처리한다")
  void detectsCredentialReplacement() {
    duringCreate.set(
        () ->
            jdbc.update(
                "UPDATE source_credentials SET access_token_enc=? WHERE connection_id=?",
                encryptor.encrypt("new-token"),
                id));
    assertThatThrownBy(this::configure)
        .hasFieldOrPropertyWithValue("errorCode", SourceErrorCode.SOURCE_CONNECTION_CHANGED);
    assertThat(deletes).containsExactly("new-1");
  }

  @Test
  @DisplayName("worker 통계와 resync 시각 변경은 충돌이 아니며 최신 값을 보존한다")
  void preservesConcurrentWorkerWrite() {
    duringCreate.set(
        () ->
            jdbc.update(
                "UPDATE source_connections SET captures_read_count=29, last_synced_at=now(), updated_at=now(), resync_requested_at=now() WHERE id=?",
                id));
    var result = configure();
    assertThat(result.capturesReadCount()).isEqualTo(29);
    assertThat(result.lastSyncedAt()).isNotNull();
    assertThat(result.resyncRequestedAt()).isNotNull();
  }

  @Test
  @DisplayName("동시 configure는 한 요청만 저장하고 뒤늦은 요청의 webhook은 정리한다")
  void concurrentConfigure() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    duringCreate.set(
        () -> {
          entered.countDown();
          await(release);
        });
    try (var executor = Executors.newSingleThreadExecutor()) {
      var first =
          executor.submit(
              () ->
                  assertThatThrownBy(this::configure)
                      .hasFieldOrPropertyWithValue(
                          "errorCode", SourceErrorCode.SOURCE_CONNECTION_CHANGED));
      try {
        assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(configure().metadata()).containsEntry("webhook_id", "new-2");
      } finally {
        release.countDown();
      }
      first.get(10, TimeUnit.SECONDS);
    }
    assertThat(deletes).containsExactly("new-1");
  }

  @Test
  @DisplayName("deferred commit 실패 후 DB를 확인하고 신규 webhook을 보상 삭제한다")
  void commitFailureCompensates() {
    previous("ACTIVE");
    var before = row();
    jdbc.execute(
        """
        CREATE FUNCTION reject_figma_commit() RETURNS trigger LANGUAGE plpgsql AS $$
        BEGIN RAISE EXCEPTION 'private-db-error'; RETURN NEW; END $$
        """);
    jdbc.execute(
        """
        CREATE CONSTRAINT TRIGGER reject_figma_commit AFTER UPDATE ON source_connections
        DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION reject_figma_commit()
        """);
    try {
      assertThatThrownBy(this::configure).isInstanceOf(RuntimeException.class);
      assertThat(row()).isEqualTo(before);
      assertThat(deletes).containsExactly("new-1");
    } finally {
      jdbc.execute("DROP TRIGGER reject_figma_commit ON source_connections");
      jdbc.execute("DROP FUNCTION reject_figma_commit()");
    }
  }

  @Test
  @DisplayName("commit 성공 재확인 시 신규 webhook은 보존하고 이전 webhook만 삭제한다")
  void unknownCommitCleansPreviousWebhook() {
    assertUnknownCommitPreservesWebhook(false);
  }

  @Test
  @DisplayName("commit 결과 재조회도 실패하면 webhook을 보존하고 reconcile 경고를 남긴다")
  void unknownCommitAndLookupFailureRequireReconciliation(CapturedOutput output) {
    assertUnknownCommitPreservesWebhook(true);
    assertThat(output.getOut())
        .contains(
            "WARN",
            "Figma configure outcome unknown",
            "connectionId=" + id,
            "webhookId=new-1",
            "previousWebhookId=old",
            "action=reconcile")
        .doesNotContain("private-commit-error", "private-lookup-error", "private-token");
  }

  private void assertUnknownCommitPreservesWebhook(boolean failLookup) {
    previous("ACTIVE");
    var failure = new TransactionSystemException("private-commit-error");
    var executions = new AtomicInteger();
    var transactions =
        new TransactionTemplate(manager) {
          @Override
          public <T> T execute(TransactionCallback<T> action) {
            int execution = executions.incrementAndGet();
            if (execution == 3 && failLookup) {
              return super.execute(
                  tx -> {
                    throw new DataAccessResourceFailureException("private-lookup-error");
                  });
            }
            if (execution != 2) {
              return super.execute(action);
            }
            var completion = new AtomicReference<TransactionSynchronization>();
            super.execute(
                tx -> {
                  T result = action.doInTransaction(tx);
                  var callbacks =
                      TransactionSynchronizationManager.getSynchronizations().stream()
                          .filter(
                              sync ->
                                  sync.getClass().getEnclosingClass()
                                      == FigmaConnectionConfigurator.class)
                          .toList();
                  assertThat(callbacks).hasSize(1);
                  completion.set(callbacks.getFirst());
                  return result;
                });
            // Commit PostgreSQL for real, then simulate an indeterminate completion report.
            // Only notify the application callback; Spring resource callbacks already completed.
            completion.get().afterCompletion(TransactionSynchronization.STATUS_UNKNOWN);
            throw failure;
          }
        };
    configurator =
        configurator(
            new FigmaWebhookProperties("https://worker.example/webhooks/figma", "private-passcode"),
            Duration.ofSeconds(5),
            transactions);

    assertThatThrownBy(this::configure).isSameAs(failure);
    assertThat(executions.get()).isEqualTo(3);
    assertThat(
            jdbc.queryForObject(
                "SELECT metadata->>'webhook_id' FROM source_connections WHERE id=?",
                String.class,
                id))
        .isEqualTo("new-1");
    assertThat(row()).containsEntry("status", "ACTIVE").containsEntry("disabled_at", null);
    assertThat(creates.get()).isEqualTo(1);
    if (failLookup) {
      assertThat(deletes).isEmpty();
    } else {
      assertThat(deletes).containsExactly("old");
    }
  }

  @Test
  @DisplayName("자격 증명 만료·누락·손상은 외부 호출 전에 거부한다")
  void rejectsInvalidCredentials() {
    jdbc.update(
        "UPDATE source_credentials SET expires_at=now()-interval '1 day' WHERE connection_id=?",
        id);
    assertThatThrownBy(this::configure)
        .hasFieldOrPropertyWithValue("errorCode", SourceErrorCode.SOURCE_FIGMA_REAUTH_REQUIRED);
    jdbc.update(
        "UPDATE source_credentials SET expires_at=null, access_token_enc=? WHERE connection_id=?",
        new byte[] {1, 2, 3},
        id);
    assertThatThrownBy(this::configure)
        .hasFieldOrPropertyWithValue("errorCode", SourceErrorCode.SOURCE_FIGMA_CREDENTIAL_INVALID);
    jdbc.update("DELETE FROM source_credentials WHERE connection_id=?", id);
    assertThatThrownBy(this::configure)
        .hasFieldOrPropertyWithValue("errorCode", SourceErrorCode.SOURCE_FIGMA_REAUTH_REQUIRED);
    assertThat(creates.get()).isZero();
  }

  @Test
  @DisplayName("입력·다른 provider·workspace·서버 설정 오류는 외부 호출 없이 거부한다")
  void rejectsInvalidSetup() {
    assertThatThrownBy(
            () ->
                configurator.configure(
                    workspaceId, id, new ConfigureFigmaCommand(" ", List.of(" "))))
        .hasFieldOrPropertyWithValue("errorCode", SourceErrorCode.SOURCE_FIGMA_INVALID_CONFIG);
    assertThatThrownBy(
            () ->
                configurator.configure(
                    UUID.randomUUID(), id, new ConfigureFigmaCommand("team", List.of("f"))))
        .hasFieldOrPropertyWithValue("errorCode", SourceErrorCode.SOURCE_CONNECTION_NOT_FOUND);
    assertThatThrownBy(
            () ->
                configurator(new FigmaWebhookProperties("", ""), Duration.ofSeconds(1))
                    .configure(workspaceId, id, new ConfigureFigmaCommand("team", List.of("f"))))
        .hasFieldOrPropertyWithValue(
            "errorCode", SourceErrorCode.SOURCE_FIGMA_WEBHOOK_UNCONFIGURED);
    jdbc.update("UPDATE source_connections SET source_type='GITHUB' WHERE id=?", id);
    assertThatThrownBy(this::configure)
        .hasFieldOrPropertyWithValue("errorCode", SourceErrorCode.SOURCE_NOT_FIGMA_CONNECTION);
    assertThat(creates.get()).isZero();
  }

  @Test
  @DisplayName("OAuth 재연결은 기존 Figma 설정과 webhook ID를 보존하고 credential을 교체한다")
  void reconnectPreservesWebhookAndInvalidatesInFlightConfigure() {
    previous("ACTIVE");
    jdbc.update("UPDATE source_connections SET external_workspace_id='figma-user' WHERE id=?", id);
    UUID userId = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO users(id,email,name) VALUES (?,?, 'figma')", userId, userId + "@example.com");
    var properties =
        new SourceOAuthProperties(
            "https://api.example/callback",
            "https://app.example",
            "long-enough-state-secret-for-hs256-test",
            null,
            Duration.ofMinutes(10),
            Map.of("figma", new SourceOAuthProperties.ProviderCredentials("cid", "secret")));
    var signer =
        new OAuthStateSigner(properties.stateSecret(), properties.stateTtl(), Clock.systemUTC());
    var providerClient = mock(ProviderOAuthClient.class);
    when(providerClient.exchange(any(), anyString()))
        .thenReturn(
            Map.of(
                "access_token",
                "new-token",
                "refresh_token",
                "new-refresh",
                "user_id_string",
                "figma-user",
                "expires_in",
                7776000));
    var installer =
        new SourceInstallerImpl(
            new OAuthProviderRegistry(properties),
            signer,
            providerClient,
            encryptor,
            connections,
            credentials,
            properties,
            new TransactionTemplate(manager));
    duringCreate.set(
        () ->
            installer.completeInstall(
                new CompleteInstallCommand(
                    "code", signer.sign(new OAuthState(workspaceId, userId, "FIGMA")))));
    try {
      assertThatThrownBy(this::configure)
          .hasFieldOrPropertyWithValue("errorCode", SourceErrorCode.SOURCE_CONNECTION_CHANGED);
      var saved = connections.findById(id).orElseThrow();
      assertThat(saved.getStatus().name()).isEqualTo("PENDING");
      assertThat(saved.getMetadata())
          .containsEntry("webhook_id", "old")
          .containsEntry("file_keys", List.of("f1"));
      assertThat(encryptor.decrypt(credentials.findById(id).orElseThrow().getAccessTokenEnc()))
          .isEqualTo("new-token");
      assertThat(deletes).containsExactly("new-1");
    } finally {
      jdbc.update("UPDATE source_connections SET connected_by_user_id=null WHERE id=?", id);
      jdbc.update("DELETE FROM users WHERE id=?", userId);
    }
  }

  @Test
  @DisplayName("등록 타임아웃은 기존 연결을 유지하며 POST를 자동 재시도하지 않는다")
  void timeoutDoesNotRetryOrDeleteOldWebhook() {
    previous("ACTIVE");
    var before = row();
    CountDownLatch release = new CountDownLatch(1);
    duringCreate.set(() -> await(release));
    try {
      var shortTimeout =
          configurator(
              new FigmaWebhookProperties(
                  "https://worker.example/webhooks/figma", "private-passcode"),
              Duration.ofMillis(150));
      assertThatThrownBy(
              () ->
                  shortTimeout.configure(
                      workspaceId, id, new ConfigureFigmaCommand("team", List.of("file"))))
          .hasFieldOrPropertyWithValue("errorCode", SourceErrorCode.SOURCE_FIGMA_WEBHOOK_FAILED);
      assertThat(row()).isEqualTo(before);
      assertThat(creates.get()).isEqualTo(1);
      assertThat(deletes).isEmpty();
    } finally {
      release.countDown();
    }
  }

  private SourceConnectionDetail configure() {
    return configurator.configure(
        workspaceId, id, new ConfigureFigmaCommand("team", List.of("file")));
  }

  private void previous(String state) {
    jdbc.update(
        "UPDATE source_connections SET status=?, disabled_at=now(), metadata='{\"webhook_id\":\"old\",\"file_keys\":[\"f1\"]}'::jsonb WHERE id=?",
        state,
        id);
  }

  private Map<String, Object> row() {
    return jdbc.queryForMap("SELECT * FROM source_connections WHERE id=?", id);
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("timeout");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }
}
