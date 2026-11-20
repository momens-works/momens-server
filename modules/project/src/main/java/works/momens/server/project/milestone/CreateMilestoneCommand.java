package works.momens.server.project.milestone;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 마일스톤 생성에 필요한 입력값입니다.
 *
 * <p>{@code projectId}는 마일스톤이 속할 프로젝트의 식별자입니다. {@code workspaceId}는 해당 프로젝트에서 소속을 거슬러 올라가 호출하는 쪽에서
 * 이미 확정한 워크스페이스 식별자입니다.
 *
 * <p>{@code requesterId}는 기본 소유자를 정할 때 사용합니다. {@code ownerUserIds}가 비어 있으면 요청자만 소유자로 지정합니다.
 */
public record CreateMilestoneCommand(
    UUID projectId,
    UUID workspaceId,
    UUID requesterId,
    String name,
    String description,
    LocalDate targetDate,
    MilestoneHealthStatus healthStatus,
    Integer progress,
    String summary,
    Instant lastContextAt,
    List<UUID> ownerUserIds) {}
