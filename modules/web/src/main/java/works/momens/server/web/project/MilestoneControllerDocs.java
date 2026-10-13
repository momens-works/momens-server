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
import works.momens.server.project.core.ProjectErrorCode;
import works.momens.server.web.project.dto.request.CreateMilestoneRequest;
import works.momens.server.web.project.dto.response.WebMilestoneResponse;

@Tag(name = "Web", description = "웹 진입 API")
interface MilestoneControllerDocs {

  @Operation(
      operationId = "createMilestone",
      summary = "마일스톤 생성",
      description = "프로젝트에 마일스톤을 생성하고 소유자 목록과 함께 반환합니다. 소유자를 지정하지 않으면 요청자가 소유자로 지정됩니다.")
  @ApiResponse(
      responseCode = "201",
      description = "마일스톤 생성 성공",
      content = @Content(schema = @Schema(implementation = WebMilestoneResponse.class)))
  @ApiException(
      value = ProjectErrorCode.class,
      codes = {"PROJECT_NOT_FOUND"})
  @ApiException(CommonErrorCode.class)
  WebMilestoneResponse createMilestone(
      @Parameter(description = "프로젝트 식별자") UUID projectId,
      CreateMilestoneRequest request,
      Principal principal);
}
