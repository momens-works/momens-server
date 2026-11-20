package works.momens.server.web.project;

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
import works.momens.server.web.project.dto.request.CreateProjectRequest;
import works.momens.server.web.project.dto.response.WebProjectResponse;
import works.momens.server.workspace.core.WorkspaceErrorCode;

@Tag(name = "Web", description = "웹 진입 API")
interface ProjectControllerDocs {

  @Operation(
      operationId = "createProject",
      summary = "프로젝트 생성",
      description = "워크스페이스에 프로젝트를 생성하고, 발급된 라벨과 소유자 목록을 포함해 반환합니다. 입력값이 허용 범위를 벗어나면 400을 반환합니다.")
  @ApiResponse(
      responseCode = "201",
      description = "프로젝트 생성 성공",
      content = @Content(schema = @Schema(implementation = WebProjectResponse.class)))
  @ApiException(
      value = WorkspaceErrorCode.class,
      codes = {"WORKSPACE_NOT_FOUND"})
  @ApiException(CommonErrorCode.class)
  WebProjectResponse createProject(
      @Parameter(description = "워크스페이스 식별자") UUID workspaceId,
      CreateProjectRequest request,
      Principal principal);
}
