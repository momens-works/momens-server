package works.momens.server.project.milestone.internal;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import works.momens.server.common.api.FieldValidationException;
import works.momens.server.workspace.membership.WorkspaceMembershipReader;

/**
 * 마일스톤을 생성할 때 소유자 목록을 검증합니다.
 *
 * <p>project core도 같은 검증 규칙을 사용하지만 별도 하위 도메인이므로 자기 경계 안에서 독립적으로 관리합니다.
 *
 * <p>워크스페이스 존재 여부와 요청자의 권한은 확인하지 않습니다. 해당 검증은 {@code :web} 모듈에서 담당합니다.
 */
@Component
@RequiredArgsConstructor
class MilestoneOwnerMembershipChecker {

  private static final String FIELD_OWNER_USER_IDS = "owner_user_ids";

  private final WorkspaceMembershipReader workspaceMembershipReader;

  /**
   * 소유자로 지정한 사용자가 모두 해당 워크스페이스의 멤버인지 확인합니다.
   *
   * <p>같은 사용자를 두 번 전달하면 해당 사용자가 멤버이더라도 요청을 거부합니다.
   *
   * <p>워크스페이스 멤버십은 한 번만 조회합니다. 소유자별로 조회하면 요청받은 소유자 수만큼 쿼리가 증가합니다.
   */
  void requireWorkspaceMembers(UUID workspaceId, List<UUID> ownerUserIds) {
    if (ownerUserIds.isEmpty()) {
      throw FieldValidationException.forField(FIELD_OWNER_USER_IDS);
    }
    Set<UUID> members = Set.copyOf(workspaceMembershipReader.listMemberUserIds(workspaceId));
    long matched = ownerUserIds.stream().distinct().filter(members::contains).count();
    if (matched != ownerUserIds.size()) {
      throw FieldValidationException.forField(FIELD_OWNER_USER_IDS);
    }
  }
}
