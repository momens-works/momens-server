package works.momens.server.workspace.membership;

import java.util.UUID;

/**
 * 멤버 제거 요청 값.
 *
 * <p>자기 자신을 제거하려는 요청인지 판정해야 하므로 요청자 ID를 함께 받습니다.
 */
public record RemoveMembershipCommand(UUID workspaceId, UUID userId, UUID targetUserId) {}
