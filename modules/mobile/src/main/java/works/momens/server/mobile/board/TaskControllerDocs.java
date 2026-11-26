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
import works.momens.server.mobile.board.dto.request.ToggleChecklistItemRequest;
import works.momens.server.mobile.board.dto.request.UpdateTaskRequest;
import works.momens.server.mobile.board.dto.response.ChecklistToggleResponse;
import works.momens.server.mobile.board.dto.response.TaskDetailResponse;
import works.momens.server.project.task.TaskErrorCode;
import works.momens.server.user.UserErrorCode;

@Tag(name = "Mobile", description = "모바일 앱 진입 API")
interface TaskControllerDocs {

  @Operation(
      operationId = "mobileGetTask",
      summary = "작업 상세 조회",
      description = "작업 상세 화면에 필요한 정보를 조회합니다.")
  @ApiResponse(
      responseCode = "200",
      description = "상세 조회 성공",
      content = @Content(schema = @Schema(implementation = TaskDetailResponse.class)))
  @ApiException(
      value = TaskErrorCode.class,
      codes = {"TASK_NOT_FOUND"})
  @ApiException(
      value = UserErrorCode.class,
      codes = {"USER_NOT_FOUND"})
  @ApiException(CommonErrorCode.class)
  TaskDetailResponse getTaskDetail(
      @Parameter(description = "task 식별자") UUID taskId, Principal principal);

  @Operation(
      operationId = "mobileUpdateTask",
      summary = "작업 수정",
      description =
          "수정 화면의 편집 상태 전체로 작업을 갱신합니다. 생략한 선택 필드는 삭제하며, 필드별 예외는 각 필드 설명을 따릅니다. 본문은 반환하지"
              + " 않으므로 저장 후 최신 상태는 작업 상세 조회로 다시 읽습니다.")
  @ApiResponse(responseCode = "204", description = "수정 성공")
  @ApiException(
      value = TaskErrorCode.class,
      codes = {"TASK_NOT_FOUND", "TASK_CHECKLIST_ITEM_NOT_FOUND"})
  @ApiException(CommonErrorCode.class)
  void updateTask(
      @Parameter(description = "task 식별자") UUID taskId,
      UpdateTaskRequest request,
      Principal principal);

  @Operation(
      operationId = "mobileDeleteTask",
      summary = "작업 삭제",
      description =
          "태스크 작성자가 태스크를 소프트 삭제합니다. 작성자가 아니거나 작성자 정보가 없는 태스크는 AUTH_FORBIDDEN으로 거부합니다. 삭제된 태스크는 보드 조회, 상세 조회, 진행률 계산에서 제외되며 복구 API를 통해 복구할 수 있습니다.")
  @ApiResponse(responseCode = "204", description = "삭제 성공")
  @ApiException(
      value = TaskErrorCode.class,
      codes = {"TASK_NOT_FOUND"})
  @ApiException(CommonErrorCode.class)
  void deleteTask(
      @Parameter(description = "task 식별자") UUID taskId,
      @Parameter(
              description = "조회한 태스크의 버전. 생략하면 요청 처리 중 발생한 동시 변경만 검사합니다.",
              schema = @Schema(implementation = Long.class, minimum = "0"))
          Long version,
      Principal principal);

  @Operation(
      operationId = "mobileRestoreTask",
      summary = "작업 복구",
      description =
          "태스크 작성자가 소프트 삭제된 태스크를 복구합니다. 복구한 태스크는 삭제 직전의 상태, 담당자, role, 완료 기준을 유지합니다. 서버에서는 복구 기한을 제한하지 않으며, 실행 취소 기능의 제공 시간은 앱에서 결정합니다. 이미 복구되었거나 삭제되지 않은 태스크는 상태를 변경하지 않고 `204`를 반환합니다. 본문은 반환하지 않으므로 복구 후 최신 상태는 보드나 상세 조회로 다시 읽습니다.")
  @ApiResponse(responseCode = "204", description = "복구 성공")
  @ApiException(
      value = TaskErrorCode.class,
      codes = {"TASK_NOT_FOUND"})
  @ApiException(CommonErrorCode.class)
  void restoreTask(@Parameter(description = "task 식별자") UUID taskId, Principal principal);

  @Operation(
      operationId = "mobileToggleChecklistItem",
      summary = "완료 기준 완료 상태 변경",
      description = "완료 기준 항목 하나의 완료 상태를 변경합니다. 변경한 항목과 함께 작업 전체 완료 수, 전체 수를 반환합니다.")
  @ApiResponse(
      responseCode = "200",
      description = "변경 성공",
      content = @Content(schema = @Schema(implementation = ChecklistToggleResponse.class)))
  @ApiException(
      value = TaskErrorCode.class,
      codes = {"TASK_NOT_FOUND", "TASK_CHECKLIST_ITEM_NOT_FOUND"})
  @ApiException(
      value = UserErrorCode.class,
      codes = {"USER_NOT_FOUND"})
  @ApiException(CommonErrorCode.class)
  ChecklistToggleResponse toggleChecklistItem(
      @Parameter(description = "task 식별자") UUID taskId,
      @Parameter(description = "완료 기준 항목 식별자") UUID itemId,
      ToggleChecklistItemRequest request,
      Principal principal);
}
