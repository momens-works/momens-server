package works.momens.server.workspace.membership;

import java.util.UUID;

/**
 * 멤버 역할 변경 요청 값.
 *
 * <p>요청자 ID는 포함하지 않습니다. 역할 변경 여부를 판단할 때 요청자의 신원을 사용하는 도메인 규칙이 없기 때문입니다. 요청자의 역할이 충분한지는 호출하는 쪽에서 미리
 * 확인합니다.
 */
public record ChangeMembershipRoleCommand(
    UUID workspaceId, UUID targetUserId, WorkspaceRole role) {}
