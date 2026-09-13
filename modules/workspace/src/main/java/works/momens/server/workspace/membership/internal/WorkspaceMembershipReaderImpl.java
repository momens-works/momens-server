package works.momens.server.workspace.membership.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import works.momens.server.workspace.membership.UserWorkspaceMembership;
import works.momens.server.workspace.membership.WorkspaceMembershipDetail;
import works.momens.server.workspace.membership.WorkspaceMembershipReader;
import works.momens.server.workspace.membership.WorkspaceRole;

/** 멤버십 엔티티를 조회해 public API의 결과 타입으로 변환하는 구현입니다. */
@Service
@RequiredArgsConstructor
class WorkspaceMembershipReaderImpl implements WorkspaceMembershipReader {

  private final WorkspaceMemberRepository workspaceMemberRepository;

  @Override
  @Transactional(readOnly = true)
  public Optional<WorkspaceRole> roleOf(UUID workspaceId, UUID userId) {
    return workspaceMemberRepository
        .findByWorkspaceIdAndUserId(workspaceId, userId)
        .map(WorkspaceMember::getRole)
        .flatMap(WorkspaceRole::from);
  }

  @Override
  @Transactional(readOnly = true)
  public List<UUID> listMemberUserIds(UUID workspaceId) {
    return workspaceMemberRepository
        .findByWorkspaceIdOrderByCreatedAtAscUserIdAsc(workspaceId)
        .stream()
        .map(WorkspaceMember::getUserId)
        .toList();
  }

  @Override
  @Transactional(readOnly = true)
  public List<WorkspaceMembershipDetail> listMembershipDetails(UUID workspaceId) {
    return workspaceMemberRepository
        .findByWorkspaceIdOrderByCreatedAtAscUserIdAsc(workspaceId)
        .stream()
        .map(
            member ->
                new WorkspaceMembershipDetail(
                    member.getUserId(),
                    member.getRole(),
                    member.getCreatedAt(),
                    member.getUpdatedAt()))
        .toList();
  }

  @Override
  @Transactional(readOnly = true)
  public List<UserWorkspaceMembership> listUserMemberships(UUID userId) {
    return workspaceMemberRepository.findByUserId(userId).stream()
        .map(member -> new UserWorkspaceMembership(member.getWorkspaceId(), member.getRole()))
        .toList();
  }
}
