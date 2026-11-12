package works.momens.server.source.connection;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import works.momens.server.common.api.BusinessException;
import works.momens.server.source.ConfigureFigmaCommand;
import works.momens.server.source.SourceConnectionDetail;
import works.momens.server.source.SourceConnectionWriter;
import works.momens.server.source.SourceErrorCode;
import works.momens.server.source.connection.oauth.FigmaConnectionConfigurator;
import works.momens.server.source.connection.oauth.FigmaWebhookCleaner;

@Service
@RequiredArgsConstructor
class SourceConnectionWriterImpl implements SourceConnectionWriter {

  private final SourceConnectionRepository sourceConnectionRepository;
  private final TransactionTemplate transactionTemplate;
  private final FigmaWebhookCleaner figmaWebhookCleaner;
  private final FigmaConnectionConfigurator figmaConnectionConfigurator;

  @Override
  @Transactional(propagation = Propagation.NEVER)
  public SourceConnectionDetail configureFigma(
      UUID workspaceId, UUID connectionId, ConfigureFigmaCommand command) {
    return figmaConnectionConfigurator.configure(workspaceId, connectionId, command);
  }

  @Override
  @Transactional(propagation = Propagation.NEVER)
  public void disable(UUID workspaceId, UUID connectionId) {
    String webhookId =
        transactionTemplate.execute(status -> {
          SourceConnection connection =
              sourceConnectionRepository
                  .findForUpdate(connectionId, workspaceId)
                  .orElseThrow(() ->
                      new BusinessException(
                          SourceErrorCode.SOURCE_CONNECTION_NOT_FOUND,
                          Map.of("source_connection_id", connectionId.toString())));
          if (connection.getStatus() == SourceConnectionStatus.DISABLED) {
            throw new BusinessException(
                SourceErrorCode.SOURCE_CONNECTION_ALREADY_DISABLED,
                Map.of("connection_id", connectionId));
          }
          sourceConnectionRepository.disable(
              connectionId, SourceConnectionStatus.DISABLED, Instant.now());
          if (!"FIGMA".equals(connection.getSourceType()) || connection.getMetadata() == null) {
            return null;
          }
          Object value = connection.getMetadata().get("webhook_id");
          return value instanceof String id && !id.isBlank() ? id.strip() : null;
        });
    // commit 실패 시 도달하지 않습니다. HTTP 호출 중에는 DB 행 잠금을 유지하지 않습니다.
    if (webhookId != null) {
      figmaWebhookCleaner.delete(connectionId, webhookId);
    }
  }

  @Override
  @Transactional
  public void requestResync(UUID connectionId, UUID workspaceId) {
    if (sourceConnectionRepository.requestResync(connectionId, workspaceId) == 0) {
      throw new BusinessException(
          SourceErrorCode.SOURCE_CONNECTION_NOT_FOUND,
          Map.of("source_connection_id", connectionId.toString()));
    }
  }
}
