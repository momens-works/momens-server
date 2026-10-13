package works.momens.server.memory;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * snapshot 목록과 태스크 context가 같은 메모리 모델을 사용합니다. 좁은 모델로 병합하면 관계 정보가 사라질 수 있습니다(MOM-0876).
 *
 * @param sourceRefIds SQL NULL과 빈 배열은 빈 목록으로 정규화합니다. 응답에서는 빈 목록을 키 생략으로 직렬화합니다.
 * @param relatedEntityIds {@code sourceRefIds}와 같은 규칙입니다.
 * @param metadata 값이 없으면 {@code null}입니다.
 */
public record ConfirmedMemoryDetail(
    UUID id,
    UUID workspaceId,
    String label,
    String memoryType,
    String title,
    String summary,
    String body,
    String status,
    List<UUID> sourceRefIds,
    List<UUID> relatedEntityIds,
    UUID createdFromCandidateId,
    UUID confirmedByUserId,
    Instant confirmedAt,
    Instant validFrom,
    Instant validUntil,
    Instant invalidatedAt,
    UUID invalidatedByUserId,
    String invalidationReason,
    Map<String, Object> metadata,
    Instant createdAt,
    Instant updatedAt) {

  public ConfirmedMemoryDetail {
    // 정규화만 하고 방어적 복사는 하지 않습니다. List.copyOf 는 원소가 null 이면 NPE 를 던지는데,
    // uuid[] 는 원소 null 을 허용하므로 값 하나 때문에 snapshot 응답 전체가 죽을 수 있습니다.
    sourceRefIds = sourceRefIds == null ? List.of() : sourceRefIds;
    relatedEntityIds = relatedEntityIds == null ? List.of() : relatedEntityIds;
  }
}
