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
import works.momens.server.context.ContextErrorCode;
import works.momens.server.memory.MemoryErrorCode;
import works.momens.server.project.task.TaskErrorCode;
import works.momens.server.web.dto.response.WebMessageResponse;
import works.momens.server.web.task.dto.request.CreateTaskSourceRefRequest;
import works.momens.server.web.task.dto.response.CreateTaskSourceRefResponse;

@Tag(name = "Web", description = "웹 진입 API")
interface TaskLinkControllerDocs {

  @Operation(
      operationId = "linkTaskMemory",
      summary = "작업에 메모리 연결",
      description = "워크스페이스 멤버가 작업에 확정 메모리를 연결합니다. 이미 연결되어 있으면 변경하지 않고 같은 응답을 반환합니다.")
  @ApiResponse(
      responseCode = "201",
      description = "연결 성공",
      content = @Content(schema = @Schema(implementation = WebMessageResponse.class)))
  @ApiException(
      value = TaskErrorCode.class,
      codes = {"TASK_NOT_FOUND"})
  @ApiException(
      value = MemoryErrorCode.class,
      codes = {"MEMORY_NOT_FOUND"})
  @ApiException(
      value = ContextErrorCode.class,
      codes = {"CONTEXT_CROSS_WORKSPACE_LINK_NOT_ALLOWED"})
  @ApiException(CommonErrorCode.class)
  WebMessageResponse linkTaskMemory(
      @Parameter(description = "작업 식별자") UUID taskId,
      @Parameter(description = "메모리 식별자") UUID memoryId,
      Principal principal);

  @Operation(
      operationId = "unlinkTaskMemory",
      summary = "작업의 메모리 연결 해제",
      description = "워크스페이스 멤버가 작업과 메모리의 연결을 해제합니다. 해제할 연결이 없으면 404로 응답합니다.")
  @ApiResponse(
      responseCode = "200",
      description = "해제 성공",
      content = @Content(schema = @Schema(implementation = WebMessageResponse.class)))
  @ApiException(
      value = TaskErrorCode.class,
      codes = {"TASK_NOT_FOUND"})
  @ApiException(
      value = ContextErrorCode.class,
      codes = {"CONTEXT_LINK_NOT_FOUND"})
  @ApiException(CommonErrorCode.class)
  WebMessageResponse unlinkTaskMemory(
      @Parameter(description = "작업 식별자") UUID taskId,
      @Parameter(description = "메모리 식별자") UUID memoryId,
      Principal principal);

  @Operation(
      operationId = "createTaskSourceRef",
      summary = "작업에 링크 첨부",
      description =
          "워크스페이스 멤버가 입력한 주소로 source-ref를 생성하고 작업에 연결합니다. 같은 주소를 두 번 첨부하면 source-ref가 두 건 생성됩니다.")
  @ApiResponse(
      responseCode = "201",
      description = "생성 성공",
      content = @Content(schema = @Schema(implementation = CreateTaskSourceRefResponse.class)))
  @ApiException(
      value = TaskErrorCode.class,
      codes = {"TASK_NOT_FOUND"})
  @ApiException(CommonErrorCode.class)
  CreateTaskSourceRefResponse createTaskSourceRef(
      @Parameter(description = "작업 식별자") UUID taskId,
      CreateTaskSourceRefRequest request,
      Principal principal);

  @Operation(
      operationId = "unlinkTaskSourceRef",
      summary = "작업의 source-ref 연결 해제",
      description = "워크스페이스 멤버가 작업과 source-ref의 연결을 해제합니다. source-ref 행은 삭제하지 않습니다.")
  @ApiResponse(
      responseCode = "200",
      description = "해제 성공",
      content = @Content(schema = @Schema(implementation = WebMessageResponse.class)))
  @ApiException(
      value = TaskErrorCode.class,
      codes = {"TASK_NOT_FOUND"})
  @ApiException(
      value = ContextErrorCode.class,
      codes = {"CONTEXT_LINK_NOT_FOUND"})
  @ApiException(CommonErrorCode.class)
  WebMessageResponse unlinkTaskSourceRef(
      @Parameter(description = "작업 식별자") UUID taskId,
      @Parameter(description = "source-ref 식별자") UUID sourceRefId,
      Principal principal);
}
