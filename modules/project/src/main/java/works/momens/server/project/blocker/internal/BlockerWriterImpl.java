package works.momens.server.project.blocker.internal;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import works.momens.server.common.api.BusinessException;
import works.momens.server.outbox.OutboxAppender;
import works.momens.server.project.blocker.BlockerDetail;
import works.momens.server.project.blocker.BlockerErrorCode;
import works.momens.server.project.blocker.BlockerStatus;
import works.momens.server.project.blocker.BlockerWriter;

@Service
@RequiredArgsConstructor
class BlockerWriterImpl implements BlockerWriter {
  private final BlockerRepository blockerRepository;
  private final OutboxAppender outboxAppender;

  @Override
  @Transactional
  public BlockerDetail createForTask(UUID workspaceId, UUID taskId, String description) {
    Blocker blocker =
        blockerRepository.saveAndFlush(Blocker.createForTask(workspaceId, taskId, description));
    outboxAppender.append(
        workspaceId, "blocker", blocker.getId().toString(), "blocker.created", Map.of());
    return blocker.toDetail();
  }

  @Override
  @Transactional
  public void resolve(UUID workspaceId, UUID blockerId) {
    findBlocker(workspaceId, blockerId);
    // 잠금을 얻은 뒤 한 시각으로 세 컬럼만 갱신합니다. JPA auditing과 별도 시계를 쓰지 않습니다.
    blockerRepository.resolve(
        workspaceId, blockerId, BlockerStatus.RESOLVED.value(), Instant.now());
    outboxAppender.appendWithIdempotencyKey(
        workspaceId,
        "blocker",
        blockerId.toString(),
        "blocker.resolved",
        Map.of(),
        "blocker.resolved:" + blockerId + ":" + UUID.randomUUID());
  }

  @Override
  @Transactional
  public void delete(UUID workspaceId, UUID blockerId) {
    Blocker blocker = findBlocker(workspaceId, blockerId);
    blockerRepository.delete(blocker);
    blockerRepository.flush();
    outboxAppender.append(
        workspaceId, "blocker", blockerId.toString(), "blocker.deleted", Map.of());
  }

  private Blocker findBlocker(UUID workspaceId, UUID blockerId) {
    return blockerRepository
        .findForUpdate(workspaceId, blockerId)
        .orElseThrow(() -> new BusinessException(BlockerErrorCode.BLOCKER_NOT_FOUND));
  }
}
