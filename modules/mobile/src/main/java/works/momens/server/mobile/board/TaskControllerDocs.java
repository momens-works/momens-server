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
      description =
          "작업 상세 화면에 필요한 정보를 조회합니다. materials는 작업에 연결된 소스이고 연결이 없으면 빈 배열입니다."
              + " open_questions, next_action은 저장된 값을 그대로 반환하며, 값이 없을 때만 각각 빈 배열, null입니다.")
  @ApiResponse(
      responseCode = "200",
      description = "상세 조회 성공. 담당자 미지정이면 assignee가, 설명 작성 전이면 purpose가 null입니다.",
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
          "수정 화면이 저장한 편집 상태 전체로 작업을 갱신합니다. 제목, 업무 역할, 담당자, 우선순위, 상태, 설명, 완료 기준을 한 번에 저장합니다. 담당자를"
              + " 비우려면 assignee_id를 null로 보내고, 완료 기준은 최종 목록으로 전체 교체하며 0개에서 5개까지 허용합니다. 저장만 하고 본문은"
              + " 반환하지 않으므로, 저장 후 최신 상태는 작업 상세 조회로 다시 읽습니다.")
  @ApiResponse(responseCode = "204", description = "수정 성공. 본문 없음. 최신 상태는 상세 조회로 읽습니다.")
  @ApiException(
      value = TaskErrorCode.class,
      codes = {"TASK_NOT_FOUND", "TASK_CHECKLIST_ITEM_NOT_FOUND", "TASK_WRITES_PAUSED"})
  @ApiException(CommonErrorCode.class)
  void updateTask(
      @Parameter(description = "task 식별자") UUID taskId,
      UpdateTaskRequest request,
      Principal principal);

  @Operation(
      operationId = "mobileDeleteTask",
      summary = "태스크 삭제",
      description =
          "태스크 작성자가 태스크를 소프트 삭제합니다. 작성자가 아니거나 작성자 정보가 없는 태스크는 AUTH_FORBIDDEN으로 거부합니다. 삭제된 태스크는 보드 조회, 상세 조회, 진행률 계산에서 제외되며 복구 API를 통해 복구할 수 있습니다.")
  @ApiResponse(responseCode = "204", description = "삭제 성공. 응답 본문 없음.")
  @ApiException(
      value = TaskErrorCode.class,
      codes = {"TASK_NOT_FOUND", "TASK_WRITES_PAUSED"})
  @ApiException(CommonErrorCode.class)
  void deleteTask(
      @Parameter(description = "task 식별자") UUID taskId,
      @Parameter(description = "수정 충돌 검사용 버전. 생략하면 충돌 검사를 수행하지 않습니다.") Long version,
      Principal principal);

  @Operation(
      operationId = "mobileRestoreTask",
      summary = "태스크 복구",
      description =
          "태스크 작성자가 소프트 삭제된 태스크를 복구합니다. 복구한 태스크는 삭제 직전의 상태, 담당자, role, 완료 기준을 유지합니다. 서버에서는 복구 기한을 제한하지 않으며, 실행 취소 기능의 제공 시간은 앱에서 결정합니다.")
  @ApiResponse(
      responseCode = "204",
      description = "복구 성공. 응답 본문 없음. 최신 상태는 보드 또는 상세 조회 API를 통해 다시 조회합니다.")
  @ApiException(
      value = TaskErrorCode.class,
      codes = {"TASK_NOT_FOUND", "TASK_NOT_DELETED", "TASK_WRITES_PAUSED"})
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
      codes = {"TASK_NOT_FOUND", "TASK_CHECKLIST_ITEM_NOT_FOUND", "TASK_WRITES_PAUSED"})
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
