package works.momens.server.source.connection.oauth;

import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import works.momens.server.common.api.BusinessException;
import works.momens.server.source.ConfigureFigmaCommand;
import works.momens.server.source.SourceConnectionDetail;
import works.momens.server.source.SourceErrorCode;
import works.momens.server.source.connection.SourceConnection;
import works.momens.server.source.connection.SourceConnectionRepository;
import works.momens.server.source.connection.SourceConnectionStatus;
import works.momens.server.source.connection.SourceCredential;
import works.momens.server.source.connection.SourceCredentialRepository;

/**
 * 새 webhook을 만든 뒤, 읽었던 연결·credential이 그대로일 때만 활성화합니다. HTTP 호출 중 DB 트랜잭션/행 잠금을 유지하지 않으며, worker가
 * 갱신하는 시각·통계는 충돌 기준에서 제외합니다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FigmaConnectionConfigurator {
  private final SourceConnectionRepository connections;
  private final SourceCredentialRepository credentials;
  private final TokenEncryptor encryptor;
  private final FigmaWebhookClient client;
  private final FigmaWebhookCleaner cleaner;
  private final FigmaWebhookProperties properties;
  private final TransactionTemplate transactions;

  @Transactional(propagation = Propagation.NEVER)
  public SourceConnectionDetail configure(
      UUID workspaceId, UUID connectionId, ConfigureFigmaCommand command) {
    String teamId = command.teamId() == null ? "" : command.teamId().strip();
    List<String> fileKeys =
        command.fileKeys() == null
            ? List.of()
            : command.fileKeys().stream()
                .filter(Objects::nonNull)
                .map(String::strip)
                .filter(key -> !key.isEmpty())
                .distinct()
                .toList();
    if (teamId.isEmpty() || fileKeys.isEmpty()) {
      throw new BusinessException(SourceErrorCode.SOURCE_FIGMA_INVALID_CONFIG, Map.of());
    }
    Snapshot before = transactions.execute(tx -> snapshot(workspaceId, connectionId));
    if (!properties.isConfigured()) {
      throw new BusinessException(SourceErrorCode.SOURCE_FIGMA_WEBHOOK_UNCONFIGURED, Map.of());
    }
    Object previous =
        before.connection().getMetadata() == null
            ? null
            : before.connection().getMetadata().get("webhook_id");
    String previousWebhookId = previous instanceof String id ? id.strip() : null;
    String token = decrypt(before.credential());
    String webhookId = client.create(token, teamId, workspaceId, properties);
    // DB 결과가 불명확하면 새 webhook을 삭제하지 않습니다. 잘못 삭제하면 저장된 ACTIVE 연결을 끊습니다.
    int[] completion = {TransactionSynchronization.STATUS_ROLLED_BACK};
    SourceConnectionDetail saved;
    try {
      saved =
          transactions.execute(tx -> {
            completion[0] = TransactionSynchronization.STATUS_UNKNOWN;
            TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                  @Override
                  public void afterCompletion(int status) {
                    completion[0] = status;
                  }
                });
            Snapshot current = snapshot(workspaceId, connectionId);
            if (!before.matches(current)) {
              throw new BusinessException(
                  SourceErrorCode.SOURCE_CONNECTION_CHANGED,
                  Map.of("source_connection_id", connectionId.toString()));
            }
            Map<String, Object> metadata = new LinkedHashMap<>();
            if (current.connection().getMetadata() != null) {
              metadata.putAll(current.connection().getMetadata());
            }
            metadata.put("team_id", teamId);
            metadata.put("file_keys", fileKeys);
            metadata.put("webhook_id", webhookId);
            connections.configureFigma(
                connectionId, metadata, SourceConnectionStatus.ACTIVE, Instant.now());
            return toDetail(connections.findById(connectionId).orElseThrow());
          });
    } finally {
      if (completion[0] == TransactionSynchronization.STATUS_ROLLED_BACK) {
        cleaner.deleteWithToken(connectionId, webhookId, token);
      } else if (completion[0] == TransactionSynchronization.STATUS_UNKNOWN) {
        cleanupIfNotStored(workspaceId, connectionId, webhookId, previousWebhookId, token);
      }
    }
    deletePreviousWebhook(connectionId, previousWebhookId, webhookId, token);
    return saved;
  }

  private void deletePreviousWebhook(
      UUID connectionId, String previousWebhookId, String webhookId, String token) {
    if (previousWebhookId != null
        && !previousWebhookId.isBlank()
        && !previousWebhookId.equals(webhookId)) {
      cleaner.deleteWithToken(connectionId, previousWebhookId, token);
    }
  }

  private void cleanupIfNotStored(
      UUID workspaceId,
      UUID connectionId,
      String webhookId,
      String previousWebhookId,
      String token) {
    try {
      // commit 결과를 모를 때 새 transaction의 행 잠금으로 먼저 DB 결과를 확인합니다.
      boolean stored =
          Boolean.TRUE.equals(
              transactions.execute(tx ->
                  connections
                      .findForUpdate(connectionId, workspaceId)
                      .map(connection ->
                          connection.getMetadata() != null
                              && webhookId.equals(connection.getMetadata().get("webhook_id")))
                      .orElse(false)));
      if (stored) {
        deletePreviousWebhook(connectionId, previousWebhookId, webhookId, token);
      } else {
        cleaner.deleteWithToken(connectionId, webhookId, token);
      }
    } catch (DataAccessException | TransactionException e) {
      log.warn(
          "Figma configure outcome unknown connectionId={} webhookId={} previousWebhookId={} action=reconcile",
          connectionId,
          webhookId,
          previousWebhookId);
    }
  }

  private Snapshot snapshot(UUID workspaceId, UUID connectionId) {
    SourceConnection connection =
        connections
            .findForUpdate(connectionId, workspaceId)
            .orElseThrow(() ->
                new BusinessException(
                    SourceErrorCode.SOURCE_CONNECTION_NOT_FOUND,
                    Map.of("source_connection_id", connectionId.toString())));
    if (!"FIGMA".equals(connection.getSourceType())) {
      throw new BusinessException(SourceErrorCode.SOURCE_NOT_FIGMA_CONNECTION, Map.of());
    }
    SourceCredential credential =
        credentials
            .findById(connectionId)
            .orElseThrow(() ->
                new BusinessException(SourceErrorCode.SOURCE_FIGMA_REAUTH_REQUIRED, Map.of()));
    if (credential.getExpiresAt() != null && !credential.getExpiresAt().isAfter(Instant.now())) {
      throw new BusinessException(SourceErrorCode.SOURCE_FIGMA_REAUTH_REQUIRED, Map.of());
    }
    return new Snapshot(connection, credential);
  }

  private String decrypt(SourceCredential credential) {
    String token;
    try {
      token = encryptor.decrypt(credential.getAccessTokenEnc());
    } catch (IllegalArgumentException | IllegalStateException e) {
      throw new BusinessException(SourceErrorCode.SOURCE_FIGMA_CREDENTIAL_INVALID, Map.of());
    }
    if (token.isBlank()) {
      throw new BusinessException(SourceErrorCode.SOURCE_FIGMA_REAUTH_REQUIRED, Map.of());
    }
    return token;
  }

  private record Snapshot(SourceConnection connection, SourceCredential credential) {
    boolean matches(Snapshot other) {
      return connection.getStatus() == other.connection.getStatus()
          && Objects.equals(connection.getConnectedAt(), other.connection.getConnectedAt())
          && Objects.equals(connection.getDisabledAt(), other.connection.getDisabledAt())
          && Objects.equals(connection.getMetadata(), other.connection.getMetadata())
          && Arrays.equals(credential.getAccessTokenEnc(), other.credential.getAccessTokenEnc())
          && Objects.equals(credential.getUpdatedAt(), other.credential.getUpdatedAt())
          && Objects.equals(credential.getExpiresAt(), other.credential.getExpiresAt());
    }
  }

  private static SourceConnectionDetail toDetail(SourceConnection connection) {
    return new SourceConnectionDetail(
        connection.getId(),
        connection.getWorkspaceId(),
        connection.getSourceType(),
        connection.getStatus().name(),
        connection.getExternalWorkspaceId(),
        connection.getExternalWorkspaceName(),
        connection.getConnectedByUserId(),
        connection.getConnectedAt(),
        connection.getLastSyncedAt(),
        connection.getDisabledAt(),
        connection.getResyncRequestedAt(),
        connection.getCapturesReadCount(),
        connection.getCandidatesExtractedCount(),
        connection.getMetadata(),
        connection.getCreatedAt(),
        connection.getUpdatedAt());
  }
}
