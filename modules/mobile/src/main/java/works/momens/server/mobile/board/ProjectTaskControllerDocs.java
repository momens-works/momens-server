package works.momens.server.mobile.board;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.security.Principal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import works.momens.server.common.api.ApiException;
import works.momens.server.common.api.CommonErrorCode;
import works.momens.server.mobile.board.dto.request.CreateTaskRequest;
import works.momens.server.mobile.board.dto.response.TaskBoardResponse;
import works.momens.server.mobile.board.dto.response.TaskCreateResponse;
import works.momens.server.project.core.ProjectErrorCode;
import works.momens.server.project.task.TaskErrorCode;
import works.momens.server.project.task.TaskPriority;
import works.momens.server.project.task.TaskRole;

@Tag(name = "Mobile", description = "모바일 앱 진입 API")
interface ProjectTaskControllerDocs {

  @Operation(
      operationId = "mobileGetTaskBoard",
      summary = "프로젝트 작업 보드 조회",
      description =
          "프로젝트 태스크를 상태별로 묶어 반환합니다. 같은 필터 안의 값은 OR, 서로 다른 필터는 AND로 결합하며, `assignee_ids`와"
              + " `unassigned`는 하나의 담당자 필터입니다. `due_date_from`과 `due_date_to`는 양 끝 날짜를 포함하는 하나의 마감일"
              + " 필터이며, 둘 중 하나라도 전달하면 마감일이 없는 태스크는 제외합니다.")
  @ApiResponse(
      responseCode = "200",
      description = "보드 조회 성공",
      content = @Content(schema = @Schema(implementation = TaskBoardResponse.class)))
  @ApiException(
      value = ProjectErrorCode.class,
      codes = {"PROJECT_NOT_FOUND"})
  @ApiException(CommonErrorCode.class)
  TaskBoardResponse getBoard(
      @Parameter(description = "project 식별자") UUID projectId,
      @Parameter(description = "지정한 역할 중 하나 이상을 가진 태스크만 반환합니다.") List<TaskRole> roles,
      @Parameter(description = "지정한 우선순위 중 하나에 해당하는 태스크만 반환합니다.") List<TaskPriority> priorities,
      @Parameter(
              description =
                  "마감일이 지정한 날짜와 같거나 이후인 태스크만 반환합니다. `due_date_to`보다 늦으면 COMMON_VALIDATION_FAILED입니다.")
          LocalDate dueDateFrom,
      @Parameter(description = "마감일이 지정한 날짜와 같거나 이전인 태스크만 반환합니다.") LocalDate dueDateTo,
      @Parameter(description = "지정한 사용자 중 한 명이 담당자인 태스크를 반환합니다.") List<UUID> assigneeIds,
      @Parameter(description = "`true`이면 담당자가 없는 태스크를 반환합니다.") boolean unassigned,
      Principal principal);

  @Operation(
      operationId = "mobileCreateTask",
      summary = "일반 작업 생성",
      description = "일반 작업을 생성합니다. 생성된 작업은 todo 상태로 시작합니다.")
  @ApiResponse(
      responseCode = "201",
      description = "생성 성공",
      content = @Content(schema = @Schema(implementation = TaskCreateResponse.class)))
  @ApiException(
      value = ProjectErrorCode.class,
      codes = {"PROJECT_NOT_FOUND"})
  @ApiException(
      value = TaskErrorCode.class,
      codes = {"TASK_WRITES_PAUSED"})
  @ApiException(CommonErrorCode.class)
  TaskCreateResponse createTask(
      @Parameter(description = "project 식별자") UUID projectId,
      CreateTaskRequest request,
      Principal principal);
}
