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
 * <p>{@code requesterId}는 소유자 기본값을 결정하는 마지막 기준입니다. {@code ownerUserIds}가 비어 있으면 프로젝트 소유자를 그대로 사용하고,
 * 프로젝트 소유자도 없으면 요청자 한 명을 소유자로 지정합니다.
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
