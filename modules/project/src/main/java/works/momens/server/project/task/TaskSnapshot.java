package works.momens.server.project.task;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** 태스크의 표면 중립적인 저장값 투영입니다. 각 표면이 필요한 응답 폭으로 매핑합니다. */
public record TaskSnapshot(
    UUID id,
    UUID workspaceId,
    UUID projectId,
    UUID milestoneId,
    String label,
    String title,
    String description,
    String status,
    String priority,
    String role,
    UUID assigneeId,
    LocalDate dueDate,
    Instant createdAt,
    Instant updatedAt,
    long version) {}
