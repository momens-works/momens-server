package works.momens.server.workspace.invitation.internal;

import java.time.Instant;
import works.momens.server.workspace.invitation.WorkspaceInvitationDetail;

/**
 * 초대 엔티티를 public API 반환 타입으로 변환합니다.
 *
 * <p>만료 상태는 DB에 저장하지 않고 이 클래스에서 계산합니다. 레거시는 목록 조회에서만 만료 상태를 계산하지만, 생성과 재발송은 만료 시각을 새로 지정하고 폐기는 상태를
 * 변경하므로 네 경로의 결과는 동일합니다. 만료 상태 계산을 한곳에 두어 경로별로 판정 규칙이 달라지지 않도록 합니다.
 */
final class InvitationDetailMapper {

  private InvitationDetailMapper() {}

  static WorkspaceInvitationDetail toDetail(WorkspaceInvitation invitation, Instant now) {
    return new WorkspaceInvitationDetail(
        invitation.getId(),
        invitation.getWorkspaceId(),
        invitation.getEmail(),
        invitation.getRole(),
        invitation.getInviterId(),
        invitation.effectiveStatus(now),
        invitation.getExpiresAt(),
        invitation.getAcceptedAt(),
        invitation.getRevokedAt(),
        invitation.getLastSentAt(),
        invitation.getCreatedAt(),
        invitation.getUpdatedAt());
  }
}
