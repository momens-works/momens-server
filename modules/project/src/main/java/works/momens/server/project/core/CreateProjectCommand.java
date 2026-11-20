package works.momens.server.project.core;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 프로젝트 생성에 필요한 입력값입니다.
 *
 * <p>{@code workspaceId}는 호출하는 쪽에서 권한을 확인하는 과정에서 이미 확정한 값이며, 이 모듈에서는 다시 조회하지 않습니다.
 *
 * <p>{@code requesterId}는 소유자 기본값을 결정할 때 사용합니다. {@code ownerUserIds}가 비어 있으면 요청자 한 명을 소유자로 지정합니다.
 * 소유자 목록을 전달한 경우와 기본값을 적용한 경우 모두 목록의 첫 번째 사용자를 {@code projects.owner_id}에 저장합니다.
 *
 * <p>{@code progress}, {@code unresolvedCount}, {@code vocSignalCount}를 {@link Integer}로 선언한 것은 값을
 * 전달하지 않은 경우와 0을 전달한 경우를 구분하기 위해서입니다. 레거시는 값이 없으면 범위 검증을 생략하고 0을 저장합니다.
 *
 * <p>{@code metadata}는 워크스페이스 생성 과정에서 함께 저장하는 프로젝트에만 사용합니다. 해당 프로젝트는 {@code seeded} 값을 저장해 사용자가 생성한
 * 프로젝트와 구분합니다. 레거시 프로젝트 생성(H037)은 해당 값을 입력받지 않으므로 웹 요청 DTO에는 노출하지 않고 해당 command에서만 관리합니다.
 */
public record CreateProjectCommand(
    UUID workspaceId,
    UUID requesterId,
    String name,
    String description,
    LocalDate targetDate,
    ProjectHealthStatus healthStatus,
    Integer progress,
    String summary,
    Integer unresolvedCount,
    Integer vocSignalCount,
    Instant lastContextAt,
    List<UUID> ownerUserIds,
    Map<String, Object> metadata) {}
