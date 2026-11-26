package works.momens.server.web.task;

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
import works.momens.server.project.task.TaskErrorCode;
import works.momens.server.web.dto.response.WebMessageResponse;
import works.momens.server.web.task.dto.request.CreateTaskUpdateRequest;
import works.momens.server.web.task.dto.request.CreateWebTaskRequest;
import works.momens.server.web.task.dto.request.UpdateWebTaskRequest;
import works.momens.server.web.task.dto.response.TaskUpdateListResponse.UpdateResponse;
import works.momens.server.web.task.dto.response.WebTaskResponse;

@Tag(name = "Web", description = "웹 진입 API")
interface TaskWriteControllerDocs {

  @Operation(
      operationId = "createProjectTask",
      summary = "프로젝트 작업 생성",
      description = "워크스페이스 멤버가 프로젝트에 작업을 생성합니다.")
  @ApiResponse(
      responseCode = "201",
      description = "생성 성공",
      content = @Content(schema = @Schema(implementation = WebTaskResponse.class)))
  @ApiException(
      value = ProjectErrorCode.class,
      codes = {"PROJECT_NOT_FOUND"})
  @ApiException(CommonErrorCode.class)
  WebTaskResponse create(
      @Parameter(description = "프로젝트 식별자") UUID projectId,
      CreateWebTaskRequest request,
      Principal principal);

  @Operation(
      operationId = "updateTask",
      summary = "작업 수정",
      description = "워크스페이스 멤버가 전달한 필드만 수정합니다.")
  @ApiResponse(
      responseCode = "200",
      description = "수정 성공",
      content = @Content(schema = @Schema(implementation = WebTaskResponse.class)))
  @ApiException(
      value = TaskErrorCode.class,
      codes = {"TASK_NOT_FOUND"})
  @ApiException(CommonErrorCode.class)
  WebTaskResponse update(
      @Parameter(description = "작업 식별자") UUID taskId,
      UpdateWebTaskRequest request,
      Principal principal);

  @Operation(
      operationId = "deleteTask",
      summary = "작업 삭제",
      description = "워크스페이스 멤버가 작업을 소프트 삭제합니다.")
  @ApiResponse(
      responseCode = "200",
      description = "삭제 성공",
      content = @Content(schema = @Schema(implementation = WebMessageResponse.class)))
  @ApiException(
      value = TaskErrorCode.class,
      codes = {"TASK_NOT_FOUND"})
  @ApiException(CommonErrorCode.class)
  WebMessageResponse delete(
      @Parameter(description = "작업 식별자") UUID taskId,
      @Parameter(description = "조회한 작업 버전. 생략하면 요청 처리 중의 동시 변경만 검사합니다.") Long version,
      Principal principal);

  @Operation(
      operationId = "createTaskUpdate",
      summary = "작업 업데이트 생성",
      description = "워크스페이스 멤버가 작업에 업데이트를 작성합니다.")
  @ApiResponse(
      responseCode = "201",
      description = "생성 성공",
      content = @Content(schema = @Schema(implementation = UpdateResponse.class)))
  @ApiException(
      value = TaskErrorCode.class,
      codes = {"TASK_NOT_FOUND"})
  @ApiException(CommonErrorCode.class)
  UpdateResponse createUpdate(
      @Parameter(description = "작업 식별자") UUID taskId,
      CreateTaskUpdateRequest request,
      Principal principal);

  @Operation(
      operationId = "deleteTaskUpdate",
      summary = "작업 업데이트 삭제",
      description = "작성자만 자신의 작업 업데이트를 소프트 삭제할 수 있습니다.")
  @ApiResponse(
      responseCode = "200",
      description = "삭제 성공",
      content = @Content(schema = @Schema(implementation = WebMessageResponse.class)))
  @ApiException(
      value = TaskErrorCode.class,
      codes = {"TASK_NOT_FOUND"})
  @ApiException(CommonErrorCode.class)
  WebMessageResponse deleteUpdate(
      @Parameter(description = "작업 식별자") UUID taskId,
      @Parameter(description = "작업 업데이트 식별자") UUID updateId,
      Principal principal);
}
