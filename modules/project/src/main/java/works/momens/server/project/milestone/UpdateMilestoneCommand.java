package works.momens.server.project.milestone;

import java.time.LocalDate;
import java.util.UUID;

public record UpdateMilestoneCommand(
    UUID milestoneId,
    String name,
    String description,
    String status,
    LocalDate targetDate,
    String healthStatus,
    Integer progress,
    String summary) {}
