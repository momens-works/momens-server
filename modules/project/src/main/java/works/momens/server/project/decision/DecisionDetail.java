package works.momens.server.project.decision;

import java.time.Instant;
import java.util.UUID;

public record DecisionDetail(
    UUID id,
    UUID projectId,
    String title,
    String context,
    String alternatives,
    String rationale,
    String reversibility,
    UUID decisionMaker,
    Instant createdAt,
    Instant updatedAt) {}
