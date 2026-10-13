package works.momens.server.outbox.internal;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import works.momens.server.outbox.OutboxEventReader;
import works.momens.server.outbox.OutboxEventView;

@Component
@RequiredArgsConstructor
class OutboxEventReaderImpl implements OutboxEventReader {

  private final OutboxEventRepository outboxEventRepository;

  @Override
  @Transactional(readOnly = true)
  public List<OutboxEventView> readAfter(long afterId, Duration safetyLag, int limit) {
    return outboxEventRepository.findDuePrefixAfter(afterId, safetyLag.toMillis(), limit).stream()
        .map(OutboxEventReaderImpl::toView)
        .toList();
  }

  @Override
  @Transactional(readOnly = true)
  public long latestIdBefore(Duration safetyLag) {
    return outboxEventRepository.findLatestIdBeforeFreshPrefix(safetyLag.toMillis());
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<OutboxEventView> findById(long id) {
    return outboxEventRepository.findById(id).map(OutboxEventReaderImpl::toView);
  }

  private static OutboxEventView toView(OutboxEvent event) {
    return new OutboxEventView(
        event.getId(),
        event.getWorkspaceId(),
        event.getAggregateType(),
        event.getAggregateId(),
        event.getEventType(),
        event.getCreatedAt());
  }
}
