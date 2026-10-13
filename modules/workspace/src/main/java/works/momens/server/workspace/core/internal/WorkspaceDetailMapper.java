package works.momens.server.workspace.core.internal;

import works.momens.server.workspace.core.WorkspaceDetail;

final class WorkspaceDetailMapper {

  private WorkspaceDetailMapper() {}

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
