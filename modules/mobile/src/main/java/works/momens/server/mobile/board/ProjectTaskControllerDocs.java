package works.momens.server.mobile.board;

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
import works.momens.server.mobile.board.dto.request.CreateTaskRequest;
import works.momens.server.mobile.board.dto.response.TaskBoardResponse;
import works.momens.server.mobile.board.dto.response.TaskCreateResponse;
import works.momens.server.project.core.ProjectErrorCode;

@Tag(name = "Mobile", description = "모바일 앱 진입 API")
interface ProjectTaskControllerDocs {

  @Operation(
      operationId = "mobileGetTaskBoard",
      summary = "프로젝트 작업 보드 조회",
      description =
          "작업을 todo, in_progress, done, backlog, cancelled 다섯 그룹으로 조회합니다. 수정 화면이 상태 5종을 모두 편집하므로 보드도"
              + " 5종을 담습니다.")
  @ApiResponse(
      responseCode = "200",
      description = "보드 조회 성공. 다섯 그룹을 항상 포함하고, 비어 있으면 tasks는 빈 배열입니다.",
      content = @Content(schema = @Schema(implementation = TaskBoardResponse.class)))
  @ApiException(
      value = ProjectErrorCode.class,
      codes = {"PROJECT_NOT_FOUND"})
  @ApiException(CommonErrorCode.class)
  TaskBoardResponse getBoard(
      @Parameter(description = "project 식별자") UUID projectId, Principal principal);

  @Operation(
      operationId = "mobileCreateTask",
      summary = "일반 작업 생성",
      description =
          "제목과 업무 역할, 우선순위로 일반 작업을 생성합니다. 세 필드 모두 필수이고 업무 역할은 하나만 선택합니다. 생성된 작업은 todo 그룹에서 시작합니다.")
  @ApiResponse(
      responseCode = "201",
      description = "생성 성공",
      content = @Content(schema = @Schema(implementation = TaskCreateResponse.class)))
  @ApiException(
      value = ProjectErrorCode.class,
      codes = {"PROJECT_NOT_FOUND"})
  @ApiException(CommonErrorCode.class)
  TaskCreateResponse createTask(
      @Parameter(description = "project 식별자") UUID projectId,
      CreateTaskRequest request,
      Principal principal);
}
