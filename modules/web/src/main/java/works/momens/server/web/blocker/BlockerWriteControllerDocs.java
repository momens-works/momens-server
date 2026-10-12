package works.momens.server.web.blocker;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.security.Principal;
import java.util.UUID;
import works.momens.server.common.api.ApiException;
import works.momens.server.common.api.CommonErrorCode;
import works.momens.server.project.blocker.BlockerErrorCode;
import works.momens.server.project.task.TaskErrorCode;
import works.momens.server.web.blocker.dto.request.CreateBlockerRequest;
import works.momens.server.web.blocker.dto.response.BlockerResponse;
import works.momens.server.web.dto.response.WebMessageResponse;

@Tag(name = "Web", description = "웹 진입 API")
interface BlockerWriteControllerDocs {
  @Operation(
      operationId = "createTaskBlocker",
      summary = "작업 차단 사유 생성",
      description = "워크스페이스 멤버가 작업의 차단 사유를 생성합니다. 초기 상태는 active입니다.")
  @ApiResponse(
      responseCode = "201",
      description = "생성 성공",
      content =
          @Content(
              schema = @Schema(implementation = BlockerResponse.class),
              examples =
                  @ExampleObject(
                      value =
                          """
              {"id":"876d3562-b48b-45af-91ef-1e35a4358374",
               "workspace_id":"5d2f7f3a-5db1-4f2c-8b9e-13607dd1f5e8",
               "description":"배포 권한 승인이 필요합니다.","status":"active",
               "blocked_entity_type":"task","blocked_entity_id":"4da2a4c9-a891-49dd-9bbd-801b2efea2af",
               "created_at":"2026-10-05T00:00:00Z","updated_at":"2026-10-05T00:00:00Z"}
              """)))
  @ApiException(
      value = TaskErrorCode.class,
      codes = {"TASK_NOT_FOUND"})
  @ApiException(
      value = BlockerErrorCode.class,
      codes = {"BLOCKER_WORKSPACE_MISMATCH"})
  @ApiException(CommonErrorCode.class)
  BlockerResponse create(UUID taskId, CreateBlockerRequest request, Principal principal);

  @Operation(
      operationId = "resolveBlocker",
      summary = "차단 사유 해결",
      description = "워크스페이스 멤버가 차단 사유를 해결합니다. 이미 해결한 차단 사유도 해결 시각을 갱신합니다.")
  @ApiResponse(
      responseCode = "200",
      description = "해결 성공",
      content =
          @Content(
              schema = @Schema(implementation = WebMessageResponse.class),
              examples = @ExampleObject(value = "{\"message\":\"resolved\"}")))
  @ApiException(
      value = BlockerErrorCode.class,
      codes = {"BLOCKER_NOT_FOUND"})
  @ApiException(CommonErrorCode.class)
  WebMessageResponse resolve(UUID blockerId, Principal principal);

  @Operation(
      operationId = "deleteBlocker",
      summary = "차단 사유 삭제",
      description = "워크스페이스 관리자 또는 소유자가 차단 사유를 영구 삭제합니다.")
  @ApiResponse(
      responseCode = "200",
      description = "삭제 성공",
      content =
          @Content(
              schema = @Schema(implementation = WebMessageResponse.class),
              examples = @ExampleObject(value = "{\"message\":\"deleted\"}")))
  @ApiException(
      value = BlockerErrorCode.class,
      codes = {"BLOCKER_NOT_FOUND"})
  @ApiException(CommonErrorCode.class)
  WebMessageResponse delete(UUID blockerId, Principal principal);
}
