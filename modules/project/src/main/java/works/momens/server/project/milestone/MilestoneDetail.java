package works.momens.server.project.milestone;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import works.momens.server.project.core.ProjectDetail;

/**
 * {@link ProjectDetail}과 달리 마일스톤 진행률은 저장값을 사용합니다(ADR-0013).
 *
 * @param ownerUserIds 생성 시각·사용자 id 순서입니다. 행이 없으면 빈 목록이며, 응답에서 키를 생략해야 합니다. 프로젝트와 달리 폴백할 소유자 컬럼은
 *     없습니다.
 */
public record MilestoneDetail(
    UUID id,
    UUID projectId,
    String name,
    String description,
    LocalDate targetDate,
    String status,
    List<UUID> ownerUserIds,
    String healthStatus,
    int progress,
    String summary,
    Instant lastContextAt,
    Instant createdAt,
    Instant updatedAt,
    long version) {}
