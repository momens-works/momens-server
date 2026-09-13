package works.momens.server.workspace.membership.internal;

import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import works.momens.server.common.api.BusinessException;
import works.momens.server.user.UserProfile;
import works.momens.server.user.UserService;
import works.momens.server.workspace.WorkspaceErrorCode;
import works.momens.server.workspace.membership.AddMembershipByEmailCommand;
import works.momens.server.workspace.membership.AddMembershipCommand;
import works.momens.server.workspace.membership.ChangeMembershipRoleCommand;
import works.momens.server.workspace.membership.RemoveMembershipCommand;
import works.momens.server.workspace.membership.WorkspaceMembershipDetail;
import works.momens.server.workspace.membership.WorkspaceMembershipWriter;
import works.momens.server.workspace.membership.WorkspaceRole;

/**
 * 워크스페이스 멤버십 쓰기 기능을 구현합니다.
 *
 * <p>멤버 추가는 단일 INSERT 쿼리로 처리해 같은 사용자에 대한 요청이 동시에 들어와도 복합 기본 키가 중복 삽입을 방지하도록 합니다. 역할 변경은 조회한 엔티티의 값을
 * 변경하고 JPA 더티 체킹으로 반영하며, 멤버 제거는 엔티티를 삭제하는 방식으로 처리합니다.
 *
 * <p>owner 보호와 자기 자신을 멤버에서 제거할 수 없다는 규칙은 이 구현에서 검증합니다.
 */
@Service
@RequiredArgsConstructor
class WorkspaceMembershipWriterImpl implements WorkspaceMembershipWriter {

  private final WorkspaceMemberRepository workspaceMemberRepository;
  private final UserService userService;
  private final Clock clock = Clock.systemUTC();

  @Override
  @Transactional
  public void addByEmail(AddMembershipByEmailCommand command) {
    UserProfile invitee =
        userService
            .findByEmail(command.email())
            .orElseThrow(
                () ->
                    new BusinessException(
                        WorkspaceErrorCode.WORKSPACE_INVITEE_NOT_FOUND,
                        Map.of("email", String.valueOf(command.email()))));
    Optional<WorkspaceMember> existing =
        workspaceMemberRepository.findByWorkspaceIdAndUserId(command.workspaceId(), invitee.id());
    if (existing.isPresent()) {
      if (existing.get().getRole().equals(command.role().value())) {
        return;
      }
      throw new BusinessException(
          WorkspaceErrorCode.WORKSPACE_MEMBER_ROLE_CONFLICT,
          Map.of("user_id", invitee.id().toString()));
    }
    workspaceMemberRepository.insertIfAbsent(
        command.workspaceId(), invitee.id(), command.role().value(), clock.instant());
  }

  @Override
  @Transactional
  public Optional<WorkspaceMembershipDetail> addIfAbsent(AddMembershipCommand command) {
    int inserted =
        workspaceMemberRepository.insertIfAbsent(
            command.workspaceId(), command.userId(), command.role().value(), clock.instant());
    if (inserted == 0) {
      return Optional.empty();
    }
    return workspaceMemberRepository
        .findByWorkspaceIdAndUserId(command.workspaceId(), command.userId())
        .map(
            member ->
                new WorkspaceMembershipDetail(
                    member.getUserId(),
                    member.getRole(),
                    member.getCreatedAt(),
                    member.getUpdatedAt()));
  }

  @Override
  @Transactional
  public void changeRole(ChangeMembershipRoleCommand command) {
    WorkspaceMember member = requireMember(command.workspaceId(), command.targetUserId());
    requireNotOwner(member);
    member.changeRole(command.role());
  }

  @Override
  @Transactional
  public void remove(RemoveMembershipCommand command) {
    if (command.userId().equals(command.targetUserId())) {
      throw new BusinessException(
          WorkspaceErrorCode.WORKSPACE_SELF_REMOVAL_NOT_ALLOWED,
          Map.of("user_id", command.targetUserId().toString()));
    }
    WorkspaceMember member = requireMember(command.workspaceId(), command.targetUserId());
    requireNotOwner(member);
    workspaceMemberRepository.delete(member);
  }

  private WorkspaceMember requireMember(UUID workspaceId, UUID userId) {
    return workspaceMemberRepository
        .findByWorkspaceIdAndUserId(workspaceId, userId)
        .orElseThrow(
            () ->
                new BusinessException(
                    WorkspaceErrorCode.WORKSPACE_MEMBER_NOT_FOUND,
                    Map.of("workspace_id", workspaceId.toString(), "user_id", userId.toString())));
  }

  private void requireNotOwner(WorkspaceMember member) {
    if (WorkspaceRole.OWNER.value().equals(member.getRole())) {
      throw new BusinessException(
          WorkspaceErrorCode.WORKSPACE_OWNER_PROTECTED,
          Map.of("user_id", member.getUserId().toString()));
    }
  }
}
