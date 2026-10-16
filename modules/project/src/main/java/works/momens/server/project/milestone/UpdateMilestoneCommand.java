package works.momens.server.project.milestone;

import java.time.LocalDate;
import java.util.UUID;

/** 선택 필드는 Set이 false이면 유지하고, true이면 값을 적용합니다. null과 빈 문자열은 삭제합니다. */
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
