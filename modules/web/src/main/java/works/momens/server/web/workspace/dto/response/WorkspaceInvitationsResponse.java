package works.momens.server.web.workspace.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import works.momens.server.web.workspace.dto.response.WorkspaceInvitationResponse.Invitation;
import works.momens.server.workspace.invitation.WorkspaceInvitationDetail;

@Schema(description = "워크스페이스 초대 목록 응답")
public record WorkspaceInvitationsResponse(
    @Schema(description = "생성 시각 내림차순으로 정렬된 초대 목록입니다. 결과가 없으면 빈 배열을 반환합니다.")
        List<Invitation> invitations) {

  public static WorkspaceInvitationsResponse from(List<WorkspaceInvitationDetail> details) {
    return new WorkspaceInvitationsResponse(details.stream().map(Invitation::from).toList());
  }
}
