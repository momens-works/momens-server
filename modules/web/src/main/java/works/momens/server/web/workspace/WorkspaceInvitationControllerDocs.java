package works.momens.server.web.workspace;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.security.Principal;
import java.util.UUID;
import works.momens.server.common.api.ApiException;
import works.momens.server.common.api.CommonErrorCode;
import works.momens.server.user.UserErrorCode;
import works.momens.server.web.dto.response.WebMessageResponse;
import works.momens.server.web.workspace.dto.request.AddWorkspaceMemberRequest;
import works.momens.server.web.workspace.dto.request.CreateWorkspaceInvitationRequest;
import works.momens.server.web.workspace.dto.response.WorkspaceInvitationResponse;
import works.momens.server.web.workspace.dto.response.WorkspaceInvitationsResponse;
import works.momens.server.workspace.core.WorkspaceErrorCode;
import works.momens.server.workspace.invitation.InvitationErrorCode;
import works.momens.server.workspace.membership.MembershipErrorCode;

@Tag(name = "Web", description = "웹 진입 API")
interface WorkspaceInvitationControllerDocs {

  @Operation(
      operationId = "listWorkspaceInvitations",
      summary = "워크스페이스 초대 목록 조회",
      description = "워크스페이스의 초대 목록을 생성 시각 기준 내림차순으로 조회합니다. admin 또는 owner 권한이 필요합니다.")
  @ApiResponse(
      responseCode = "200",
      description = "목록 조회 성공",
      content = @Content(schema = @Schema(implementation = WorkspaceInvitationsResponse.class)))
  @ApiException(
      value = WorkspaceErrorCode.class,
      codes = {"WORKSPACE_NOT_FOUND"})
  @ApiException(CommonErrorCode.class)
  WorkspaceInvitationsResponse list(
      @Parameter(description = "워크스페이스 식별자") UUID workspaceId, Principal principal);

  @Operation(
      operationId = "createWorkspaceInvitation",
      summary = "워크스페이스 초대 생성",
      description =
          "이메일 주소로 초대를 생성하고 초대 링크를 발송합니다. admin 또는 owner 권한이 필요합니다. 같은 이메일 주소로 대기 중인 초대가 있으면 기존"
              + " 초대를 갱신합니다.")
  @ApiResponse(
      responseCode = "201",
      description = "초대 생성 성공",
      content = @Content(schema = @Schema(implementation = WorkspaceInvitationResponse.class)))
  @ApiException(
      value = InvitationErrorCode.class,
      codes = {"INVITATION_INVALID_EMAIL", "INVITATION_EMAIL_SEND_FAILED", "INVITATION_NOT_FOUND"})
  @ApiException(
      value = WorkspaceErrorCode.class,
      codes = {"WORKSPACE_NOT_FOUND"})
  @ApiException(
      value = MembershipErrorCode.class,
      codes = {"WORKSPACE_MEMBER_ALREADY_EXISTS"})
  @ApiException(
      value = UserErrorCode.class,
      codes = {"USER_NOT_FOUND"})
  @ApiException(CommonErrorCode.class)
  WorkspaceInvitationResponse create(
      @Parameter(description = "워크스페이스 식별자") UUID workspaceId,
      CreateWorkspaceInvitationRequest request,
      Principal principal);

  @Operation(
      operationId = "resendWorkspaceInvitation",
      summary = "워크스페이스 초대 재발송",
      description =
          "초대 토큰과 만료 시각을 새로 발급한 뒤 초대 링크를 다시 발송합니다. 만료되었거나 폐기된 초대도 재발송할 수 있지만, 이미 수락된 초대는 재발송할 수"
              + " 없습니다.")
  @ApiResponse(
      responseCode = "200",
      description = "초대 재발송 성공",
      content = @Content(schema = @Schema(implementation = WorkspaceInvitationResponse.class)))
  @ApiException(
      value = InvitationErrorCode.class,
      codes = {
        "INVITATION_NOT_FOUND",
        "INVITATION_EMAIL_SEND_FAILED",
        "INVITATION_ALREADY_ACCEPTED"
      })
  @ApiException(
      value = WorkspaceErrorCode.class,
      codes = {"WORKSPACE_NOT_FOUND"})
  @ApiException(
      value = UserErrorCode.class,
      codes = {"USER_NOT_FOUND"})
  @ApiException(CommonErrorCode.class)
  WorkspaceInvitationResponse resend(
      @Parameter(description = "워크스페이스 식별자") UUID workspaceId,
      @Parameter(description = "초대 식별자") UUID invitationId,
      Principal principal);

  @Operation(
      operationId = "revokeWorkspaceInvitation",
      summary = "워크스페이스 초대 폐기",
      description = "초대를 폐기하여 기존 초대 링크를 사용할 수 없게 합니다. 이미 수락된 초대는 폐기할 수 없습니다.")
  @ApiResponse(
      responseCode = "200",
      description = "초대 폐기 성공",
      content = @Content(schema = @Schema(implementation = WorkspaceInvitationResponse.class)))
  @ApiException(
      value = InvitationErrorCode.class,
      codes = {"INVITATION_NOT_FOUND", "INVITATION_ALREADY_ACCEPTED"})
  @ApiException(
      value = WorkspaceErrorCode.class,
      codes = {"WORKSPACE_NOT_FOUND"})
  @ApiException(CommonErrorCode.class)
  WorkspaceInvitationResponse revoke(
      @Parameter(description = "워크스페이스 식별자") UUID workspaceId,
      @Parameter(description = "초대 식별자") UUID invitationId,
      Principal principal);

  @Operation(
      operationId = "addWorkspaceMember",
      summary = "워크스페이스 멤버 추가",
      description = "초대를 생성하지 않고 이메일 주소로 사용자를 찾아 워크스페이스 멤버로 바로 추가합니다. admin 또는 owner 권한이 필요합니다.")
  @ApiResponse(
      responseCode = "200",
      description = "멤버 추가 성공",
      content = @Content(schema = @Schema(implementation = WebMessageResponse.class)))
  @ApiException(
      value = WorkspaceErrorCode.class,
      codes = {"WORKSPACE_NOT_FOUND"})
  @ApiException(
      value = MembershipErrorCode.class,
      codes = {"WORKSPACE_INVITEE_NOT_FOUND", "WORKSPACE_MEMBER_ROLE_CONFLICT"})
  @ApiException(CommonErrorCode.class)
  WebMessageResponse invite(
      @Parameter(description = "워크스페이스 식별자") UUID workspaceId,
      AddWorkspaceMemberRequest request,
      Principal principal);
}
