package works.momens.server.web.memory.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import works.momens.server.memory.ConfirmedMemoryDetail;

/**
 * 확정 메모리 한 건의 웹 응답입니다.
 *
 * <p>레거시 {@code domain.ConfirmedMemory}의 JSON 그대로입니다. 레거시가 대부분의 필드에 {@code omitempty}를 달아 값이 없으면
 * <b>키 자체를 내보내지 않으므로</b> {@code NON_NULL}과 {@code NON_EMPTY}로 그것을 재현합니다. 조회 모델({@link
 * ConfirmedMemoryDetail})을 그대로 반환하면 {@code "summary": null}·{@code "source_ref_ids": []}가 나가 레거시와
 * 갈립니다({@code WorkspaceSnapshotResponse}와 같은 처리).
 *
 * <p>{@code deleted_at}은 담지 않습니다. 확정 직후의 메모리라 항상 비어 있습니다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "확정 메모리")
public record ConfirmedMemoryResponse(
    UUID id,
    UUID workspaceId,
    String label,
    String memoryType,
    String title,
    String summary,
    String body,
    String status,
    @JsonInclude(JsonInclude.Include.NON_EMPTY) List<UUID> sourceRefIds,
    @JsonInclude(JsonInclude.Include.NON_EMPTY) List<UUID> relatedEntityIds,
    UUID createdFromCandidateId,
    UUID confirmedByUserId,
    Instant confirmedAt,
    Instant validFrom,
    Instant validUntil,
    Instant invalidatedAt,
    UUID invalidatedByUserId,
    String invalidationReason,
    @JsonInclude(JsonInclude.Include.NON_EMPTY) Map<String, Object> metadata,
    Instant createdAt,
    Instant updatedAt) {

  public static ConfirmedMemoryResponse from(ConfirmedMemoryDetail memory) {
    return new ConfirmedMemoryResponse(
        memory.id(),
        memory.workspaceId(),
        memory.label(),
        memory.memoryType(),
        memory.title(),
        memory.summary(),
        memory.body(),
        memory.status(),
        memory.sourceRefIds(),
        memory.relatedEntityIds(),
        memory.createdFromCandidateId(),
        memory.confirmedByUserId(),
        memory.confirmedAt(),
        memory.validFrom(),
        memory.validUntil(),
        memory.invalidatedAt(),
        memory.invalidatedByUserId(),
        memory.invalidationReason(),
        memory.metadata(),
        memory.createdAt(),
        memory.updatedAt());
  }
}
