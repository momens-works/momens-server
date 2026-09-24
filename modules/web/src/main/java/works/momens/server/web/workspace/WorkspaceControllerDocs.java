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
import works.momens.server.web.workspace.dto.request.CreateWorkspaceRequest;
import works.momens.server.web.workspace.dto.request.UpdateWorkspaceRequest;
import works.momens.server.web.workspace.dto.response.WorkspaceListResponse;
import works.momens.server.web.workspace.dto.response.WorkspaceResponse;
import works.momens.server.web.workspace.dto.response.WorkspaceSlugAvailabilityResponse;
import works.momens.server.web.workspace.dto.response.WorkspaceSnapshotResponse;
import works.momens.server.workspace.WorkspaceErrorCode;

/**
 * {@code /api/workspaces} OpenAPI 문서. Swagger 애너테이션을 컨트롤러 구현과 분리합니다(docs/spec/openapi.md).
 *
 * <p>401/403은 보안 필터가 Standard shape로 응답하고, 없는 workspace는 WORKSPACE_NOT_FOUND(404), workspace는 있는데
 * 멤버가 아니면 AUTH_FORBIDDEN(403)입니다.
 */
@Tag(name = "Web", description = "웹 진입 API")
interface WorkspaceControllerDocs {

  @Operation(
      operationId = "listWorkspaces",
      summary = "워크스페이스 목록 조회",
      description = "요청자가 멤버인 워크스페이스 목록을 생성 시각 내림차순으로 조회합니다.")
  @ApiResponse(
      responseCode = "200",
      description = "목록 조회 성공. 결과가 없으면 workspaces는 빈 배열입니다.",
      content = @Content(schema = @Schema(implementation = WorkspaceListResponse.class)))
  @ApiException(CommonErrorCode.class)
  WorkspaceListResponse list(Principal principal);

  @Operation(
      operationId = "checkWorkspaceSlugAvailability",
      summary = "slug 사용 가능 여부 조회",
      description =
          "입력한 값을 워크스페이스 slug로 사용할 수 있는지 확인합니다. 사용할 수 없는 경우에도 200으로 응답하며 판정 사유를 함께 반환합니다.")
  @ApiResponse(
      responseCode = "200",
      description = "판정 성공. 사용할 수 없으면 reason을 반환하고, 이미 사용 중이면 suggestion으로 대안을 제공합니다.",
      content =
          @Content(schema = @Schema(implementation = WorkspaceSlugAvailabilityResponse.class)))
  @ApiException(CommonErrorCode.class)
  WorkspaceSlugAvailabilityResponse slugAvailable(
      @Parameter(description = "사용 가능 여부를 확인할 slug. 값이 없으면 형식 오류로 판정합니다.") String slug);

  @Operation(
      operationId = "getWorkspace",
      summary = "워크스페이스 상세 조회",
      description = "워크스페이스 한 건을 조회합니다.")
  @ApiResponse(
      responseCode = "200",
      description = "조회 성공",
      content = @Content(schema = @Schema(implementation = WorkspaceResponse.class)))
  @ApiException(
      value = WorkspaceErrorCode.class,
      codes = {"WORKSPACE_NOT_FOUND"})
  @ApiException(CommonErrorCode.class)
  WorkspaceResponse get(
      @Parameter(description = "워크스페이스 식별자") UUID workspaceId, Principal principal);

  @Operation(
      operationId = "getWorkspaceSnapshot",
      summary = "웹 보드 snapshot 조회",
      description = "워크스페이스 보드에 필요한 read 모델을 한 응답으로 반환합니다.")
  @ApiResponse(
      responseCode = "200",
      description = "snapshot 조회 성공. 모든 목록은 비어 있으면 빈 배열입니다.",
      content = @Content(schema = @Schema(implementation = WorkspaceSnapshotResponse.class)))
  @ApiException(
      value = WorkspaceErrorCode.class,
      codes = {"WORKSPACE_NOT_FOUND"})
  @ApiException(CommonErrorCode.class)
  WorkspaceSnapshotResponse snapshot(
      @Parameter(description = "워크스페이스 식별자") UUID workspaceId, Principal principal);

  @Operation(
      operationId = "createWorkspace",
      summary = "워크스페이스 생성",
      description =
          "워크스페이스를 생성하고 이름이 Welcome인 프로젝트와 메모리 세 건을 함께 저장합니다. "
              + "이름이 비어 있거나 slug가 형식 또는 예약어 규칙에 맞지 않으면 400, 이미 사용 중인 slug이면 409를 반환합니다.")
  @ApiResponse(
      responseCode = "201",
      description = "생성 성공. 생성된 워크스페이스를 반환합니다.",
      content = @Content(schema = @Schema(implementation = WorkspaceResponse.class)))
  @ApiException(
      value = WorkspaceErrorCode.class,
      codes = {
        "WORKSPACE_INVALID_SLUG",
        "WORKSPACE_RESERVED_SLUG",
        "WORKSPACE_SLUG_ALREADY_EXISTS"
      })
  @ApiException(CommonErrorCode.class)
  WorkspaceResponse createWorkspace(CreateWorkspaceRequest request, Principal principal);

  @Operation(
      operationId = "updateWorkspace",
      summary = "워크스페이스 수정",
      description = "워크스페이스의 이름, 설명, slug를 수정합니다. admin 이상의 권한이 필요합니다.")
  @ApiResponse(
      responseCode = "200",
      description = "수정 성공. 변경된 워크스페이스를 반환합니다.",
      content = @Content(schema = @Schema(implementation = WorkspaceResponse.class)))
  @ApiException(
      value = WorkspaceErrorCode.class,
      codes = {
        "WORKSPACE_NOT_FOUND",
        "WORKSPACE_INVALID_SLUG",
        "WORKSPACE_RESERVED_SLUG",
        "WORKSPACE_SLUG_ALREADY_EXISTS"
      })
  @ApiException(CommonErrorCode.class)
  WorkspaceResponse update(
      @Parameter(description = "워크스페이스 식별자") UUID workspaceId,
      UpdateWorkspaceRequest request,
      Principal principal);
}
