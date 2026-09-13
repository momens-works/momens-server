package works.momens.server.web.workspace.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import works.momens.server.workspace.invitation.WorkspaceInvitationDetail;

@Schema(description = "워크스페이스 초대 한 건 응답")
public record WorkspaceInvitationResponse(@Schema(description = "초대 정보") Invitation invitation) {

  public static WorkspaceInvitationResponse from(WorkspaceInvitationDetail detail) {
    return new WorkspaceInvitationResponse(Invitation.from(detail));
  }

  @Schema(description = "워크스페이스 초대 정보")
  public record Invitation(
      @Schema(description = "초대 식별자") UUID id,
      @Schema(description = "워크스페이스 식별자") UUID workspaceId,
      @Schema(description = "초대받은 이메일") String email,
      @Schema(description = "초대 수락 시 부여할 역할입니다. admin 또는 member입니다.") String role,
      @JsonInclude(JsonInclude.Include.NON_NULL)
          @Schema(description = "초대한 사용자 식별자입니다. 값이 없으면 응답에서 생략됩니다.", nullable = true)
          UUID inviterId,
      @Schema(description = "초대 상태입니다. pending, accepted, revoked, expired 중 하나입니다.") String status,
      @Schema(description = "만료 시각(UTC)") Instant expiresAt,
      @JsonInclude(JsonInclude.Include.NON_NULL)
          @Schema(description = "수락 시각입니다. 값이 없으면 응답에서 생략됩니다.", nullable = true)
          Instant acceptedAt,
      @JsonInclude(JsonInclude.Include.NON_NULL)
          @Schema(description = "폐기 시각입니다. 값이 없으면 응답에서 생략됩니다.", nullable = true)
          Instant revokedAt,
      @JsonInclude(JsonInclude.Include.NON_NULL)
          @Schema(description = "마지막 발송 시각입니다. 값이 없으면 응답에서 생략됩니다.", nullable = true)
          Instant lastSentAt,
      @Schema(description = "생성 시각(UTC)") Instant createdAt,
      @Schema(description = "수정 시각(UTC)") Instant updatedAt) {

    public static Invitation from(WorkspaceInvitationDetail detail) {
      return new Invitation(
          detail.id(),
          detail.workspaceId(),
          detail.email(),
          detail.role(),
          detail.inviterId(),
          detail.status().value(),
          detail.expiresAt(),
          detail.acceptedAt(),
          detail.revokedAt(),
          detail.lastSentAt(),
          detail.createdAt(),
          detail.updatedAt());
    }
  }
}
