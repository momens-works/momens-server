package works.momens.server.project.blocker;

import java.util.UUID;

/** 호출자가 대상 태스크의 소속과 권한을 확인한 후 사용하는 blocker 쓰기 API. */
public interface BlockerWriter {
  /** workspaceId는 호출자가 대상 태스크·프로젝트에서 확인한 값입니다. */
  BlockerDetail createForTask(UUID workspaceId, UUID taskId, String description);

  void resolve(UUID workspaceId, UUID blockerId);

  void delete(UUID workspaceId, UUID blockerId);
}
