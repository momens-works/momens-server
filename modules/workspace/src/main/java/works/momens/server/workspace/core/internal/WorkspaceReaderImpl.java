package works.momens.server.workspace.core.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import works.momens.server.workspace.core.WorkspaceDetail;
import works.momens.server.workspace.core.WorkspaceReader;
import works.momens.server.workspace.membership.UserWorkspaceMembership;
import works.momens.server.workspace.membership.WorkspaceMembershipReader;

@Service
@RequiredArgsConstructor
class WorkspaceReaderImpl implements WorkspaceReader {

  private final WorkspaceRepository workspaceRepository;
  private final WorkspaceMembershipReader workspaceMembershipReader;

  @Override
  @Transactional(readOnly = true)
  public Optional<WorkspaceDetail> findById(UUID workspaceId) {
    return workspaceRepository.findById(workspaceId).map(WorkspaceDetailMapper::toDetail);
  }

  /**
   * 사용자가 속한 워크스페이스를 조회합니다.
   *
   * <p>먼저 {@code membership} 하위 도메인의 공개 계약인 {@code WorkspaceMembershipReader}를 통해 멤버십을 확인합니다. 이후
   * 확정된 워크스페이스 ID를 기준으로 이 하위 도메인의 테이블만 조회합니다. 멤버십이 없으면 데이터베이스를 조회하지 않고 빈 목록을 반환합니다.
   */
  @Override
  @Transactional(readOnly = true)
  public List<WorkspaceDetail> listByMemberUserId(UUID userId) {
    List<UUID> workspaceIds =
        workspaceMembershipReader.listUserMemberships(userId).stream()
            .map(UserWorkspaceMembership::workspaceId)
            .toList();
    if (workspaceIds.isEmpty()) {
      return List.of();
    }
    return workspaceRepository.findByIdInOrderByCreatedAtDesc(workspaceIds).stream()
        .map(WorkspaceDetailMapper::toDetail)
        .toList();
  }
}
