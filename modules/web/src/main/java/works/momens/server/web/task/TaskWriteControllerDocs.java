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

/** {@code /api} 웹 태스크 쓰기 OpenAPI 문서. */
@Tag(name = "Web", description = "웹 진입 API")
interface TaskWriteControllerDocs {

  @Operation(
      operationId = "createProjectTask",
      summary = "프로젝트 태스크 생성",
      description = "워크스페이스 멤버가 프로젝트에 태스크를 생성합니다.")
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
      summary = "태스크 수정",
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
      @Parameter(description = "태스크 식별자") UUID taskId,
      UpdateWebTaskRequest request,
      Principal principal);

  @Operation(
      operationId = "deleteTask",
      summary = "태스크 삭제",
      description = "워크스페이스 멤버가 태스크를 소프트 삭제합니다.")
  @ApiResponse(
      responseCode = "200",
      description = "삭제 성공",
      content = @Content(schema = @Schema(implementation = WebMessageResponse.class)))
  @ApiException(
      value = TaskErrorCode.class,
      codes = {"TASK_NOT_FOUND"})
  @ApiException(CommonErrorCode.class)
  WebMessageResponse delete(@Parameter(description = "태스크 식별자") UUID taskId, Principal principal);

  @Operation(
      operationId = "createTaskUpdate",
      summary = "태스크 업데이트 생성",
      description = "워크스페이스 멤버가 태스크에 업데이트를 작성합니다.")
  @ApiResponse(
      responseCode = "201",
      description = "생성 성공",
      content = @Content(schema = @Schema(implementation = UpdateResponse.class)))
  @ApiException(
      value = TaskErrorCode.class,
      codes = {"TASK_NOT_FOUND"})
  @ApiException(CommonErrorCode.class)
  UpdateResponse createUpdate(
      @Parameter(description = "태스크 식별자") UUID taskId,
      CreateTaskUpdateRequest request,
      Principal principal);

  @Operation(
      operationId = "deleteTaskUpdate",
      summary = "태스크 업데이트 삭제",
      description = "작성자만 자신의 태스크 업데이트를 소프트 삭제할 수 있습니다.")
  @ApiResponse(
      responseCode = "200",
      description = "삭제 성공",
      content = @Content(schema = @Schema(implementation = WebMessageResponse.class)))
  @ApiException(
      value = TaskErrorCode.class,
      codes = {"TASK_NOT_FOUND"})
  @ApiException(CommonErrorCode.class)
  WebMessageResponse deleteUpdate(
      @Parameter(description = "태스크 식별자") UUID taskId,
      @Parameter(description = "태스크 업데이트 식별자") UUID updateId,
      Principal principal);
}
