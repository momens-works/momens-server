package works.momens.server.source.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import works.momens.server.common.api.BusinessException;
import works.momens.server.common.test.AbstractPostgresIntegrationTest;
import works.momens.server.source.SourceConnectionWriter;
import works.momens.server.source.SourceErrorCode;
import works.momens.server.source.connection.oauth.FigmaConnectionConfigurator;
import works.momens.server.source.connection.oauth.FigmaWebhookCleaner;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
  SourceConnectionWriterImpl.class,
  SourceConnectionWriterIntegrationTest.Transactions.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("source 연결 비활성화 PostgreSQL 통합 테스트")
class SourceConnectionWriterIntegrationTest extends AbstractPostgresIntegrationTest {

  @Autowired private SourceConnectionWriter writer;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private TransactionTemplate transactions;
  @MockitoBean private FigmaWebhookCleaner cleaner;
  @MockitoBean private FigmaConnectionConfigurator configurator;
  private UUID workspaceId;

  @BeforeEach
  void setUp() {
    workspaceId = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO workspaces (id, name, slug) VALUES (?, 'disable test', ?)",
        workspaceId,
        "disable-" + workspaceId);
  }

  @AfterEach
  void tearDown() {
    jdbc.update("DELETE FROM source_connections WHERE workspace_id = ?", workspaceId);
    jdbc.update("DELETE FROM workspaces WHERE id = ?", workspaceId);
  }

  @ParameterizedTest
  @ValueSource(strings = {"PENDING", "ACTIVE", "ERROR", "REVOKED"})
  @DisplayName("허용된 각 상태를 비활성화하고 다른 컬럼은 보존한다")
  void disablesEachAllowedStateWithoutOverwritingOtherColumns(String status) {
    UUID id = insert("GITHUB", status);
    Map<String, Object> before = row(id);
    writer.disable(workspaceId, id);
    Map<String, Object> after = row(id);
    assertThat(after.get("status")).isEqualTo("DISABLED");
    assertThat(after.get("disabled_at")).isNotNull();
    assertThat(after.get("updated_at")).isEqualTo(after.get("disabled_at"));
    for (String key : new String[] {"status", "disabled_at", "updated_at"}) {
      before.remove(key);
      after.remove(key);
    }
    assertThat(after).isEqualTo(before);
    verifyNoInteractions(cleaner);
  }

  @Test
  @DisplayName("반복 비활성화는 시각을 변경하거나 webhook을 다시 삭제하지 않는다")
  void repeatDoesNotChangeDisabledTimeOrDeleteAgain() {
    UUID id = insert("FIGMA", "ACTIVE");
    writer.disable(workspaceId, id);
    Map<String, Object> disabled = row(id);
    assertThatThrownBy(() -> writer.disable(workspaceId, id))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue(
            "errorCode", SourceErrorCode.SOURCE_CONNECTION_ALREADY_DISABLED);
    assertThat(row(id)).isEqualTo(disabled);
    verify(cleaner).delete(id, "wh-1");
  }

  @Test
  @DisplayName("연결이 없거나 workspace가 다르면 쓰기와 외부 호출을 수행하지 않는다")
  void missingAndWrongWorkspaceDoNotWriteOrCallProvider() {
    UUID id = insert("FIGMA", "ACTIVE");
    Map<String, Object> before = row(id);
    assertThatThrownBy(() -> writer.disable(UUID.randomUUID(), id))
        .hasFieldOrPropertyWithValue("errorCode", SourceErrorCode.SOURCE_CONNECTION_NOT_FOUND)
        .hasFieldOrPropertyWithValue("details", Map.of("source_connection_id", id.toString()));
    UUID missingId = UUID.randomUUID();
    assertThatThrownBy(() -> writer.disable(workspaceId, missingId))
        .hasFieldOrPropertyWithValue("errorCode", SourceErrorCode.SOURCE_CONNECTION_NOT_FOUND)
        .hasFieldOrPropertyWithValue(
            "details", Map.of("source_connection_id", missingId.toString()));
    assertThat(row(id)).isEqualTo(before);
    verifyNoInteractions(cleaner);
  }

  @Test
  @DisplayName("외부 정리 호출은 DB 커밋 이후 열린 트랜잭션 없이 수행한다")
  void providerSeesCommittedStateWithoutAnOpenTransaction() {
    UUID id = insert("FIGMA", "ACTIVE");
    doAnswer(
            call -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              assertThat(row(id).get("status")).isEqualTo("DISABLED");
              return null;
            })
        .when(cleaner)
        .delete(id, "wh-1");
    writer.disable(workspaceId, id);
    verify(cleaner).delete(id, "wh-1");
  }

  @ParameterizedTest
  @ValueSource(strings = {"{}", "{\"webhook_id\":42}", "{\"webhook_id\":\"  \"}"})
  @DisplayName("유효한 webhook 식별자가 없으면 외부 정리를 호출하지 않는다")
  void noWebhookDoesNotCallProvider(String metadata) {
    UUID id = insert("FIGMA", "ACTIVE");
    jdbc.update("UPDATE source_connections SET metadata = ?::jsonb WHERE id = ?", metadata, id);
    writer.disable(workspaceId, id);
    verifyNoInteractions(cleaner);
  }

  @Test
  @DisplayName("레거시처럼 webhook 식별자의 앞뒤 공백을 제거한다")
  void trimsWebhookIdLikeLegacy() {
    UUID id = insert("FIGMA", "ACTIVE");
    jdbc.update(
        "UPDATE source_connections SET metadata = '{\"webhook_id\":\" wh-1 \"}'::jsonb WHERE id = ?",
        id);
    writer.disable(workspaceId, id);
    verify(cleaner).delete(id, "wh-1");
  }

  @Test
  @DisplayName("외부 트랜잭션 안에서 비활성화를 호출하면 변경 없이 거부한다")
  void refusesAmbientTransactionRatherThanDeletingBeforeItsCommit() {
    UUID id = insert("FIGMA", "ACTIVE");
    assertThatThrownBy(
            () -> transactions.executeWithoutResult(tx -> writer.disable(workspaceId, id)))
        .isInstanceOf(IllegalTransactionStateException.class);
    assertThat(row(id).get("status")).isEqualTo("ACTIVE");
    verifyNoInteractions(cleaner);
  }

  @Test
  @DisplayName("비활성화 커밋 실패는 전파하고 webhook은 삭제하지 않는다")
  void commitFailureDoesNotDeleteWebhook() {
    UUID id = insert("FIGMA", "ACTIVE");
    jdbc.execute(
        """
        CREATE FUNCTION reject_source_disable_commit() RETURNS trigger LANGUAGE plpgsql AS $$
        BEGIN
          IF NEW.status = 'DISABLED' THEN RAISE EXCEPTION 'test commit failure'; END IF;
          RETURN NEW;
        END $$
        """);
    jdbc.execute(
        """
        CREATE CONSTRAINT TRIGGER reject_source_disable_commit
        AFTER UPDATE ON source_connections DEFERRABLE INITIALLY DEFERRED
        FOR EACH ROW EXECUTE FUNCTION reject_source_disable_commit()
        """);
    try {
      assertThatThrownBy(() -> writer.disable(workspaceId, id))
          .isInstanceOf(RuntimeException.class);
      assertThat(row(id).get("status")).isEqualTo("ACTIVE");
      verifyNoInteractions(cleaner);
    } finally {
      jdbc.execute("DROP TRIGGER reject_source_disable_commit ON source_connections");
      jdbc.execute("DROP FUNCTION reject_source_disable_commit()");
    }
  }

  @Test
  @DisplayName("동시 비활성화는 한 요청만 상태를 전환하고 webhook을 정리한다")
  void concurrentDisableOnlyTransitionsAndCleansOnce() throws Exception {
    UUID id = insert("FIGMA", "ACTIVE");
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var first = executor.submit(() -> disableTogether(id, ready, start));
      var second = executor.submit(() -> disableTogether(id, ready, start));
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder("disabled", "SOURCE_CONNECTION_ALREADY_DISABLED");
    } finally {
      start.countDown();
    }
    verify(cleaner).delete(id, "wh-1");
  }

  private String disableTogether(UUID id, CountDownLatch ready, CountDownLatch start)
      throws Exception {
    ready.countDown();
    if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("start timed out");
    try {
      writer.disable(workspaceId, id);
      return "disabled";
    } catch (BusinessException e) {
      return e.getErrorCode().code();
    }
  }

  private UUID insert(String type, String status) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO source_connections (id, workspace_id, source_type, status, metadata,
          captures_read_count, candidates_extracted_count, last_synced_at, resync_requested_at)
        VALUES (?, ?, ?, ?, '{"webhook_id":"wh-1","file_keys":["file-1"]}'::jsonb,
          17, 9, '2026-09-01T00:00:00Z', '2026-09-02T00:00:00Z')
        """,
        id,
        workspaceId,
        type,
        status);
    return id;
  }

  private Map<String, Object> row(UUID id) {
    return jdbc.queryForMap("SELECT * FROM source_connections WHERE id = ?", id);
  }

  @TestConfiguration
  static class Transactions {
    @Bean
    TransactionTemplate transactionTemplate(PlatformTransactionManager manager) {
      return new TransactionTemplate(manager);
    }
  }
}
