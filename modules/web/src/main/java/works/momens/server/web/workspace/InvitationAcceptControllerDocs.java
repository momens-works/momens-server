package works.momens.server.web.workspace;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.security.Principal;
import works.momens.server.common.api.ApiException;
import works.momens.server.common.api.CommonErrorCode;
import works.momens.server.web.workspace.dto.request.AcceptInvitationRequest;
import works.momens.server.web.workspace.dto.response.AcceptInvitationResponse;
import works.momens.server.workspace.WorkspaceErrorCode;
import works.momens.server.workspace.invitation.InvitationErrorCode;

/**
 * {@code /api/invitations/accept} 엔드포인트의 OpenAPI 문서입니다. Swagger 애너테이션을 컨트롤러 구현과 분리합니다({@code
 * docs/spec/openapi.md}).
 *
 * <p>401 응답은 보안 필터에서 처리합니다. 초대를 찾을 수 없으면 {@code INVITATION_NOT_FOUND}(404), 초대받은 이메일 주소와 로그인한 계정의
 * 이메일 주소가 다르면 {@code INVITATION_EMAIL_MISMATCH}(403)를 반환합니다. 초대가 이미 수락되었거나 만료 또는 폐기되었거나, 사용자가 이미
 * 워크스페이스 멤버라면 409를 반환합니다.
 */
@Tag(name = "Web", description = "웹 진입 API")
interface InvitationAcceptControllerDocs {

  @Operation(
      operationId = "acceptInvitation",
      summary = "워크스페이스 초대 수락",
      description = "초대 링크에 포함된 토큰으로 워크스페이스에 참여합니다. 로그인한 계정의 이메일 주소가 초대받은 이메일 주소와 일치해야 합니다.")
  @ApiResponse(
      responseCode = "200",
      description = "초대 수락 성공",
      content = @Content(schema = @Schema(implementation = AcceptInvitationResponse.class)))
  @ApiException(
      value = InvitationErrorCode.class,
      codes = {
        "INVITATION_INVALID_TOKEN",
        "INVITATION_NOT_FOUND",
        "INVITATION_ALREADY_ACCEPTED",
        "INVITATION_REVOKED",
        "INVITATION_EXPIRED",
        "INVITATION_EMAIL_MISMATCH"
      })
  @ApiException(
      value = WorkspaceErrorCode.class,
      codes = {"WORKSPACE_INVALID_ROLE", "WORKSPACE_MEMBER_ALREADY_EXISTS", "WORKSPACE_NOT_FOUND"})
  @ApiException(CommonErrorCode.class)
  AcceptInvitationResponse accept(AcceptInvitationRequest request, Principal principal);
}
