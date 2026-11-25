package works.momens.server.mobile.roster;

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
import works.momens.server.mobile.roster.dto.response.ProjectMembersResponse;
import works.momens.server.project.core.ProjectErrorCode;

@Tag(name = "Mobile", description = "모바일 앱 진입 API")
interface ProjectMemberControllerDocs {

  @Operation(
      operationId = "mobileListProjectMembers",
      summary = "프로젝트 멤버 조회",
      description =
          "작업 담당자 선택에 사용할 프로젝트 멤버 목록을 조회합니다. 멤버 범위는 project가 속한 workspace의 멤버십입니다. 요청한 사용자를 첫"
              + " 번째로, 나머지는 이름 오름차순(같으면 id 오름차순)으로 정렬하며, 요청한 사용자도 query에 맞지 않으면 제외합니다.")
  @ApiResponse(
      responseCode = "200",
      description = "멤버 목록 조회 성공",
      content = @Content(schema = @Schema(implementation = ProjectMembersResponse.class)))
  @ApiException(
      value = ProjectErrorCode.class,
      codes = {"PROJECT_NOT_FOUND"})
  @ApiException(CommonErrorCode.class)
  ProjectMembersResponse listMembers(
      @Parameter(description = "project 식별자") UUID projectId,
      @Parameter(description = "이름 검색어. 앞뒤 공백을 지운 뒤 대소문자를 무시하고 부분 일치로 거르며, 없거나 공백이면 전체 멤버를 반환합니다.")
          String query,
      Principal principal);
}
