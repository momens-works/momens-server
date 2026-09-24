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
import works.momens.server.web.dto.response.WebMessageResponse;
import works.momens.server.web.workspace.dto.request.UpdateWorkspaceMemberRequest;
import works.momens.server.web.workspace.dto.response.WorkspaceMembersResponse;
import works.momens.server.workspace.WorkspaceErrorCode;

/**
 * {@code /api/workspaces/{workspaceId}/members} endpoint의 OpenAPI 문서. Swagger 애너테이션을 컨트롤러 구현과 분리합니다
 * ({@code docs/spec/openapi.md}).
 *
 * <p>401은 보안 필터에서 응답합니다. 워크스페이스가 없으면 {@code WORKSPACE_NOT_FOUND}(404), 워크스페이스는 있지만 요청자의 권한이 부족하면
 * {@code AUTH_FORBIDDEN}(403)을 반환합니다. 대상 사용자가 멤버가 아니면 {@code WORKSPACE_MEMBER_NOT_FOUND}(404), 대상이
 * owner이거나 요청자 자신이면 409를 반환합니다.
 */
@Tag(name = "Web", description = "웹 진입 API")
interface WorkspaceMemberControllerDocs {

  @Operation(
      operationId = "listWorkspaceMembers",
      summary = "워크스페이스 멤버 목록 조회",
      description = "워크스페이스 멤버를 가입 시각 오름차순으로 조회합니다. 가입 시각이 같으면 사용자 식별자 오름차순으로 정렬합니다.")
  @ApiResponse(
      responseCode = "200",
      description = "목록 조회 성공",
      content = @Content(schema = @Schema(implementation = WorkspaceMembersResponse.class)))
  @ApiException(
      value = WorkspaceErrorCode.class,
      codes = {"WORKSPACE_NOT_FOUND"})
  @ApiException(CommonErrorCode.class)
  WorkspaceMembersResponse list(
      @Parameter(description = "워크스페이스 식별자") UUID workspaceId, Principal principal);

  @Operation(
      operationId = "updateWorkspaceMemberRole",
      summary = "워크스페이스 멤버 역할 수정",
      description = "멤버의 역할을 변경합니다. admin 이상의 권한이 필요하며, 워크스페이스 소유자의 역할은 변경할 수 없습니다.")
  @ApiResponse(
      responseCode = "200",
      description = "수정 성공",
      content = @Content(schema = @Schema(implementation = WebMessageResponse.class)))
  @ApiException(
      value = WorkspaceErrorCode.class,
      codes = {
        "WORKSPACE_NOT_FOUND",
        "WORKSPACE_INVALID_ROLE",
        "WORKSPACE_MEMBER_NOT_FOUND",
        "WORKSPACE_OWNER_PROTECTED"
      })
  @ApiException(CommonErrorCode.class)
  WebMessageResponse update(
      @Parameter(description = "워크스페이스 식별자") UUID workspaceId,
      @Parameter(description = "대상 사용자 식별자") UUID userId,
      UpdateWorkspaceMemberRequest request,
      Principal principal);

  @Operation(
      operationId = "removeWorkspaceMember",
      summary = "워크스페이스 멤버 제거",
      description = "멤버를 워크스페이스에서 제거합니다. admin 이상의 권한이 필요하며, 자기 자신과 워크스페이스 소유자는 제거할 수 없습니다.")
  @ApiResponse(
      responseCode = "200",
      description = "제거 성공",
      content = @Content(schema = @Schema(implementation = WebMessageResponse.class)))
  @ApiException(
      value = WorkspaceErrorCode.class,
      codes = {
        "WORKSPACE_NOT_FOUND",
        "WORKSPACE_MEMBER_NOT_FOUND",
        "WORKSPACE_OWNER_PROTECTED",
        "WORKSPACE_SELF_REMOVAL_NOT_ALLOWED"
      })
  @ApiException(CommonErrorCode.class)
  WebMessageResponse remove(
      @Parameter(description = "워크스페이스 식별자") UUID workspaceId,
      @Parameter(description = "대상 사용자 식별자") UUID userId,
      Principal principal);
}
