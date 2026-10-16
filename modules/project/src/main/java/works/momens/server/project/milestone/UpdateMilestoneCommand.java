package works.momens.server.project.milestone;

import java.time.LocalDate;
import java.util.UUID;

/**
 * description, targetDate, summary는 대응하는 {@code *Set}이 false이면 유지하고, true이면 값을 적용합니다. 이 세 필드의 null과
 * description·summary의 빈 문자열은 삭제를 뜻합니다.
 */
public record UpdateMilestoneCommand(
    UUID milestoneId,
    String name,
    String description,
    boolean descriptionSet,
    String status,
    LocalDate targetDate,
    boolean targetDateSet,
    String healthStatus,
    Integer progress,
    String summary,
    boolean summarySet) {}
