package works.momens.server.project.blocker;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BlockerReader {

  Optional<UUID> workspaceIdOf(UUID blockerId);

  /** 워크스페이스의 blocker를 생성 시각 내림차순으로 조회합니다. */
  List<BlockerDetail> listDetailsByWorkspaceId(UUID workspaceId);
}
