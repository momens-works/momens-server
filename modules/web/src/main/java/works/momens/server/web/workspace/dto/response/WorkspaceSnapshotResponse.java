package works.momens.server.web.workspace.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import works.momens.server.memory.ConfirmedMemoryDetail;
import works.momens.server.memory.MemoryCandidateDetail;
import works.momens.server.project.blocker.BlockerDetail;
import works.momens.server.project.core.ProjectDetail;
import works.momens.server.project.milestone.MilestoneDetail;
import works.momens.server.project.task.TaskSnapshot;
import works.momens.server.source.LegacySourceRefDetail;
import works.momens.server.web.workspace.WorkspaceMemberView;
import works.momens.server.workspace.core.WorkspaceDetail;

/** 레거시 H023과 호환되는 웹 보드의 단일 read 응답입니다. */
public record WorkspaceSnapshotResponse(
    WorkspaceResponse workspace,
    List<WorkspaceMemberResponse> members,
    List<SnapshotProjectResponse> projects,
    List<SnapshotMilestoneResponse> milestones,
    List<SnapshotTaskResponse> tasks,
    List<SnapshotBlockerResponse> blockers,
    List<SnapshotMemoryCandidateResponse> memoryCandidates,
    List<SnapshotMemoryResponse> memories,
    List<SnapshotTaskContextResponse> taskContexts) {

  public static WorkspaceSnapshotResponse from(
      WorkspaceDetail workspace,
      List<WorkspaceMemberView> members,
      List<ProjectDetail> projects,
      List<MilestoneDetail> milestones,
      List<TaskSnapshot> tasks,
      List<BlockerDetail> blockers,
      List<MemoryCandidateDetail> candidates,
      List<ConfirmedMemoryDetail> memories,
      List<SnapshotTaskContextResponse> taskContexts) {
    return new WorkspaceSnapshotResponse(
        WorkspaceResponse.from(workspace),
        members.stream().map(WorkspaceMemberResponse::from).toList(),
        projects.stream().map(SnapshotProjectResponse::from).toList(),
        milestones.stream().map(SnapshotMilestoneResponse::from).toList(),
        tasks.stream().map(SnapshotTaskResponse::from).toList(),
        blockers.stream().map(SnapshotBlockerResponse::from).toList(),
        candidates.stream().map(SnapshotMemoryCandidateResponse::from).toList(),
        memories.stream().map(SnapshotMemoryResponse::from).toList(),
        taskContexts);
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record SnapshotProjectResponse(
      UUID id,
      UUID workspaceId,
      String label,
      String name,
      String description,
      String status,
      UUID ownerId,
      List<UUID> ownerUserIds,
      LocalDate targetDate,
      String healthStatus,
      String summary,
      int unresolvedCount,
      int vocSignalCount,
      Instant lastContextAt,
      @JsonInclude(JsonInclude.Include.NON_EMPTY) Map<String, Object> metadata,
      Instant createdAt,
      Instant updatedAt) {
    static SnapshotProjectResponse from(ProjectDetail project) {
      return new SnapshotProjectResponse(
          project.id(),
          project.workspaceId(),
          project.label(),
          project.name(),
          project.description(),
          project.status(),
          project.ownerId(),
          project.ownerUserIds(),
          project.targetDate(),
          project.healthStatus(),
          project.summary(),
          project.unresolvedCount(),
          project.vocSignalCount(),
          project.lastContextAt(),
          project.metadata(),
          project.createdAt(),
          project.updatedAt());
    }
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record SnapshotMilestoneResponse(
      UUID id,
      UUID projectId,
      String name,
      String description,
      LocalDate targetDate,
      String status,
      @JsonInclude(JsonInclude.Include.NON_EMPTY) List<UUID> ownerUserIds,
      String healthStatus,
      int progress,
      String summary,
      Instant lastContextAt,
      Instant createdAt,
      Instant updatedAt) {
    static SnapshotMilestoneResponse from(MilestoneDetail milestone) {
      return new SnapshotMilestoneResponse(
          milestone.id(),
          milestone.projectId(),
          milestone.name(),
          milestone.description(),
          milestone.targetDate(),
          milestone.status(),
          milestone.ownerUserIds(),
          milestone.healthStatus(),
          milestone.progress(),
          milestone.summary(),
          milestone.lastContextAt(),
          milestone.createdAt(),
          milestone.updatedAt());
    }
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record SnapshotTaskResponse(
      UUID id,
      UUID projectId,
      UUID milestoneId,
      String label,
      String title,
      String description,
      String status,
      String priority,
      UUID assigneeId,
      LocalDate dueDate,
      Instant createdAt,
      Instant updatedAt) {
    static SnapshotTaskResponse from(TaskSnapshot task) {
      return new SnapshotTaskResponse(
          task.id(),
          task.projectId(),
          task.milestoneId(),
          task.label(),
          task.title(),
          task.description(),
          task.status(),
          task.priority(),
          task.assigneeId(),
          task.dueDate(),
          task.createdAt(),
          task.updatedAt());
    }
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record SnapshotBlockerResponse(
      UUID id,
      UUID workspaceId,
      String description,
      String status,
      String blockedEntityType,
      UUID blockedEntityId,
      Instant createdAt,
      Instant updatedAt,
      Instant resolvedAt) {
    static SnapshotBlockerResponse from(BlockerDetail blocker) {
      return new SnapshotBlockerResponse(
          blocker.id(),
          blocker.workspaceId(),
          blocker.description(),
          blocker.status(),
          blocker.blockedEntityType(),
          blocker.blockedEntityId(),
          blocker.createdAt(),
          blocker.updatedAt(),
          blocker.resolvedAt());
    }
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record SnapshotMemoryCandidateResponse(
      UUID id,
      UUID workspaceId,
      String label,
      String candidateType,
      String title,
      String summary,
      String body,
      Double confidence,
      Double importance,
      String status,
      @JsonInclude(JsonInclude.Include.NON_EMPTY) List<UUID> sourceRefIds,
      @JsonInclude(JsonInclude.Include.NON_EMPTY) List<UUID> relatedEntityIds,
      String proposedBy,
      Instant reviewedAt,
      UUID reviewedByUserId,
      String rejectionReason,
      Instant expiresAt,
      @JsonInclude(JsonInclude.Include.NON_EMPTY) Map<String, Object> metadata,
      Instant createdAt,
      Instant updatedAt) {
    static SnapshotMemoryCandidateResponse from(MemoryCandidateDetail candidate) {
      return new SnapshotMemoryCandidateResponse(
          candidate.id(),
          candidate.workspaceId(),
          candidate.label(),
          candidate.candidateType(),
          candidate.title(),
          candidate.summary(),
          candidate.body(),
          candidate.confidence(),
          candidate.importance(),
          candidate.status(),
          candidate.sourceRefIds(),
          candidate.relatedEntityIds(),
          candidate.proposedBy(),
          candidate.reviewedAt(),
          candidate.reviewedByUserId(),
          candidate.rejectionReason(),
          candidate.expiresAt(),
          candidate.metadata(),
          candidate.createdAt(),
          candidate.updatedAt());
    }
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record SnapshotMemoryResponse(
      UUID id,
      UUID workspaceId,
      String label,
      String memoryType,
      String title,
      String summary,
      String body,
      String status,
      @JsonInclude(JsonInclude.Include.NON_EMPTY) List<UUID> sourceRefIds,
      @JsonInclude(JsonInclude.Include.NON_EMPTY) List<UUID> relatedEntityIds,
      UUID createdFromCandidateId,
      UUID confirmedByUserId,
      Instant confirmedAt,
      Instant validFrom,
      Instant validUntil,
      Instant invalidatedAt,
      UUID invalidatedByUserId,
      String invalidationReason,
      @JsonInclude(JsonInclude.Include.NON_EMPTY) Map<String, Object> metadata,
      Instant createdAt,
      Instant updatedAt) {
    public static SnapshotMemoryResponse from(ConfirmedMemoryDetail memory) {
      return new SnapshotMemoryResponse(
          memory.id(),
          memory.workspaceId(),
          memory.label(),
          memory.memoryType(),
          memory.title(),
          memory.summary(),
          memory.body(),
          memory.status(),
          memory.sourceRefIds(),
          memory.relatedEntityIds(),
          memory.createdFromCandidateId(),
          memory.confirmedByUserId(),
          memory.confirmedAt(),
          memory.validFrom(),
          memory.validUntil(),
          memory.invalidatedAt(),
          memory.invalidatedByUserId(),
          memory.invalidationReason(),
          memory.metadata(),
          memory.createdAt(),
          memory.updatedAt());
    }
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record SnapshotSourceRefResponse(
      UUID id,
      UUID workspaceId,
      String sourceType,
      String sourceObjectType,
      String sourceObjectId,
      String sourceUrl,
      String title,
      String snippet,
      String authorName,
      String authorEmail,
      Instant sourceCreatedAt,
      String visibility,
      String permissionKey,
      UUID verifiedByUserId,
      Instant verifiedAt,
      Instant createdAt,
      Instant updatedAt) {
    public static SnapshotSourceRefResponse from(LegacySourceRefDetail source) {
      return new SnapshotSourceRefResponse(
          source.id(),
          source.workspaceId(),
          source.sourceType(),
          source.sourceObjectType(),
          source.sourceObjectId(),
          source.sourceUrl(),
          source.title(),
          source.snippet(),
          source.authorName(),
          source.authorEmail(),
          source.sourceCreatedAt(),
          source.visibility(),
          source.permissionKey(),
          source.verifiedByUserId(),
          source.verifiedAt(),
          source.createdAt(),
          source.updatedAt());
    }
  }

  public record SnapshotTaskContextResponse(
      UUID taskId,
      List<SnapshotMemoryResponse> memories,
      List<SnapshotSourceRefResponse> sourceRefs) {}
}
