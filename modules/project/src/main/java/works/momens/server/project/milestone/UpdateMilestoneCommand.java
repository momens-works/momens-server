package works.momens.server.project.milestone;

import java.time.LocalDate;
import java.util.UUID;

/** MCP 마일스톤 수정에 필요한 입력값입니다. */
public record UpdateMilestoneCommand(
    UUID milestoneId,
    String name,
    String description,
    String status,
    LocalDate targetDate,
    String healthStatus,
    Integer progress,
    String summary) {}
