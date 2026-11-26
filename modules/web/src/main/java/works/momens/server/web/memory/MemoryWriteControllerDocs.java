package works.momens.server.web.memory;

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
import works.momens.server.memory.MemoryErrorCode;
import works.momens.server.web.dto.response.WebMessageResponse;
import works.momens.server.web.memory.dto.request.EditAndConfirmMemoryCandidateRequest;
import works.momens.server.web.memory.dto.request.MergeMemoryCandidateRequest;
import works.momens.server.web.memory.dto.request.RejectMemoryCandidateRequest;
import works.momens.server.web.memory.dto.request.ResolveMemoryRequest;
import works.momens.server.web.memory.dto.response.ConfirmedMemoryResponse;

@Tag(name = "Web", description = "웹 진입 API")
interface MemoryWriteControllerDocs {
  @Operation(operationId = "confirmMemoryCandidate", summary = "메모리 후보 확정")
  @ApiResponse(
      responseCode = "201",
      content = @Content(schema = @Schema(implementation = ConfirmedMemoryResponse.class)))
  @ApiException(
      value = MemoryErrorCode.class,
      codes = {"MEMORY_CANDIDATE_NOT_FOUND", "MEMORY_INVALID_STATE", "MEMORY_NOT_FOUND"})
  @ApiException(CommonErrorCode.class)
  ConfirmedMemoryResponse confirm(
      @Parameter(description = "메모리 후보 식별자") UUID candidateId, Principal principal);

  @Operation(operationId = "editAndConfirmMemoryCandidate", summary = "메모리 후보 수정 후 확정")
  @ApiResponse(
      responseCode = "201",
      content = @Content(schema = @Schema(implementation = ConfirmedMemoryResponse.class)))
  @ApiException(
      value = MemoryErrorCode.class,
      codes = {"MEMORY_CANDIDATE_NOT_FOUND", "MEMORY_INVALID_STATE", "MEMORY_NOT_FOUND"})
  @ApiException(CommonErrorCode.class)
  ConfirmedMemoryResponse editAndConfirm(
      UUID candidateId, EditAndConfirmMemoryCandidateRequest request, Principal principal);

  @Operation(operationId = "rejectMemoryCandidate", summary = "메모리 후보 거절")
  @ApiResponse(
      responseCode = "200",
      content = @Content(schema = @Schema(implementation = WebMessageResponse.class)))
  @ApiException(
      value = MemoryErrorCode.class,
      codes = {"MEMORY_CANDIDATE_NOT_FOUND", "MEMORY_INVALID_STATE"})
  @ApiException(CommonErrorCode.class)
  WebMessageResponse reject(
      UUID candidateId, RejectMemoryCandidateRequest request, Principal principal);

  @Operation(operationId = "mergeMemoryCandidate", summary = "메모리 후보 병합")
  @ApiResponse(
      responseCode = "200",
      content = @Content(schema = @Schema(implementation = WebMessageResponse.class)))
  @ApiException(
      value = MemoryErrorCode.class,
      codes = {
        "MEMORY_CANDIDATE_NOT_FOUND",
        "MEMORY_NOT_FOUND",
        "MEMORY_INVALID_STATE",
        "MEMORY_INVALID_INPUT"
      })
  @ApiException(CommonErrorCode.class)
  WebMessageResponse merge(
      UUID candidateId, MergeMemoryCandidateRequest request, Principal principal);

  @Operation(operationId = "expireMemoryCandidate", summary = "메모리 후보 만료")
  @ApiResponse(
      responseCode = "200",
      content = @Content(schema = @Schema(implementation = WebMessageResponse.class)))
  @ApiException(
      value = MemoryErrorCode.class,
      codes = {"MEMORY_CANDIDATE_NOT_FOUND", "MEMORY_INVALID_STATE"})
  @ApiException(CommonErrorCode.class)
  WebMessageResponse expire(UUID candidateId, Principal principal);

  @Operation(
      operationId = "resolveMemory",
      summary = "메모리 해결",
      description =
          "resolving_memory_id의 메모리가 이 메모리를 해결하고, 이 메모리를 보관합니다. 두 메모리 모두 ACTIVE여야 하며, 자기 자신을"
              + " 해결하려 하면 400입니다. 같은 resolving_memory_id로 이미 해결된 메모리에 요청을 다시 보내면 아무것도 바꾸지 않고"
              + " 200으로 응답하고, 그 밖에 어느 한쪽이 ACTIVE가 아니면 409입니다.")
  @ApiResponse(
      responseCode = "200",
      content = @Content(schema = @Schema(implementation = WebMessageResponse.class)))
  @ApiException(
      value = MemoryErrorCode.class,
      codes = {"MEMORY_NOT_FOUND", "MEMORY_INVALID_STATE", "MEMORY_INVALID_INPUT"})
  @ApiException(CommonErrorCode.class)
  WebMessageResponse resolve(UUID memoryId, ResolveMemoryRequest request, Principal principal);
}
