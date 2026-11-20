package works.momens.server.source.connection;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import works.momens.server.common.api.BusinessException;
import works.momens.server.source.SourceConnectionDetail;
import works.momens.server.source.SourceConnectionReader;
import works.momens.server.source.SourceErrorCode;

@Component
@RequiredArgsConstructor
class SourceConnectionReaderImpl implements SourceConnectionReader {

  private final SourceConnectionRepository sourceConnectionRepository;

  @Override
  @Transactional(readOnly = true)
  public UUID getWorkspaceId(UUID connectionId) {
    return sourceConnectionRepository
        .findWorkspaceId(connectionId)
        .orElseThrow(() ->
            new BusinessException(
                SourceErrorCode.SOURCE_CONNECTION_NOT_FOUND,
                Map.of("source_connection_id", connectionId.toString())));
  }

  @Override
  @Transactional(readOnly = true)
  public List<SourceConnectionDetail> listDetailsByWorkspaceId(UUID workspaceId) {
    return sourceConnectionRepository.findByWorkspaceIdOrderByCreatedAtDesc(workspaceId).stream()
        .map(SourceConnectionReaderImpl::toDetail)
        .toList();
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<UUID> findWorkspaceId(UUID connectionId) {
    return sourceConnectionRepository.findWorkspaceId(connectionId);
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
