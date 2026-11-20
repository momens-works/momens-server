package works.momens.server.project.blocker.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import works.momens.server.project.blocker.BlockerDetail;
import works.momens.server.project.blocker.BlockerReader;

@Service
@RequiredArgsConstructor
class BlockerReaderImpl implements BlockerReader {

  private final BlockerRepository blockerRepository;

  @Override
  @Transactional(readOnly = true)
  public Optional<UUID> workspaceIdOf(UUID blockerId) {
    return blockerRepository.findWorkspaceIdById(blockerId);
  }

  @Override
  @Transactional(readOnly = true)
  public List<BlockerDetail> listDetailsByWorkspaceId(UUID workspaceId) {
    return blockerRepository.findDetailsByWorkspaceId(workspaceId);
  }
}
