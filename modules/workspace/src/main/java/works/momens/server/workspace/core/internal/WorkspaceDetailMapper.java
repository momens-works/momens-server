package works.momens.server.workspace.core.internal;

import works.momens.server.workspace.core.WorkspaceDetail;

final class WorkspaceDetailMapper {

  private WorkspaceDetailMapper() {}

  /** {@link Workspace} 엔티티를 public API에서 사용하는 조회 결과로 변환합니다. reader와 editor가 동일한 매핑을 사용합니다. */
  static WorkspaceDetail toDetail(Workspace workspace) {
    return new WorkspaceDetail(
        workspace.getId(),
        workspace.getName(),
        workspace.getSlug(),
        workspace.getDescription(),
        workspace.getCreatedAt(),
        workspace.getUpdatedAt());
  }
}
