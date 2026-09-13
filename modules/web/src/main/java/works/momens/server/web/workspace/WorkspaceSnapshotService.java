package works.momens.server.web.workspace;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import works.momens.server.common.api.BusinessException;
import works.momens.server.context.EntityRelationReader;
import works.momens.server.context.TaskContextLinks;
import works.momens.server.memory.ConfirmedMemoryDetail;
import works.momens.server.memory.ConfirmedMemoryReader;
import works.momens.server.memory.MemoryCandidateDetail;
import works.momens.server.memory.MemoryCandidateReader;
import works.momens.server.project.blocker.BlockerDetail;
import works.momens.server.project.blocker.BlockerReader;
import works.momens.server.project.core.ProjectDetail;
import works.momens.server.project.core.ProjectDetailReader;
import works.momens.server.project.milestone.MilestoneDetail;
import works.momens.server.project.milestone.MilestoneReader;
import works.momens.server.project.task.TaskReader;
import works.momens.server.project.task.TaskSnapshot;
import works.momens.server.source.LegacySourceRefDetail;
import works.momens.server.source.SourceRefReader;
import works.momens.server.web.workspace.dto.response.WorkspaceSnapshotResponse;
import works.momens.server.workspace.WorkspaceErrorCode;
import works.momens.server.workspace.core.WorkspaceDetail;
import works.momens.server.workspace.core.WorkspaceReader;

/** 웹 보드가 한 번의 요청으로 읽는 workspace snapshot을 조합합니다. */
@Service
@RequiredArgsConstructor
class WorkspaceSnapshotService {

  private final WorkspaceReader workspaceReader;
  private final WorkspaceMemberListService workspaceMemberListService;
  private final ProjectDetailReader projectDetailReader;
  private final MilestoneReader milestoneReader;
  private final TaskReader taskReader;
  private final BlockerReader blockerReader;
  private final MemoryCandidateReader memoryCandidateReader;
  private final ConfirmedMemoryReader confirmedMemoryReader;
  private final EntityRelationReader entityRelationReader;
  private final SourceRefReader sourceRefReader;

  @Transactional(readOnly = true)
  public WorkspaceSnapshotResponse get(UUID workspaceId, UUID userId) {
    WorkspaceDetail workspace =
        workspaceReader
            .findById(workspaceId)
            .orElseThrow(
                () ->
                    new BusinessException(
                        WorkspaceErrorCode.WORKSPACE_NOT_FOUND,
                        Map.of("workspace_id", workspaceId.toString())));
    List<WorkspaceMemberView> members = workspaceMemberListService.list(workspaceId, userId);
    List<ProjectDetail> projects = projectDetailReader.listDetailsByWorkspaceId(workspaceId);
    List<MilestoneDetail> milestones = milestoneReader.listDetailsByWorkspaceId(workspaceId);
    List<TaskSnapshot> tasks = taskReader.listSnapshotsByWorkspaceId(workspaceId);
    List<BlockerDetail> blockers = blockerReader.listDetailsByWorkspaceId(workspaceId);
    List<MemoryCandidateDetail> candidates =
        memoryCandidateReader.listDetailsByWorkspaceId(workspaceId);
    List<ConfirmedMemoryDetail> memories =
        confirmedMemoryReader.listDetailsByWorkspaceId(workspaceId);

    Map<UUID, TaskContextLinks> linksByTaskId =
        entityRelationReader.findTaskContextLinks(
            workspaceId, tasks.stream().map(TaskSnapshot::id).toList());
    LinkedHashSet<UUID> memoryIds = new LinkedHashSet<>();
    LinkedHashSet<UUID> sourceRefIds = new LinkedHashSet<>();
    linksByTaskId
        .values()
        .forEach(
            links -> {
              memoryIds.addAll(links.memoryIds());
              sourceRefIds.addAll(links.sourceRefIds());
            });
    List<ConfirmedMemoryDetail> linkedMemories =
        confirmedMemoryReader.findDetailsByIds(workspaceId, memoryIds);
    List<LegacySourceRefDetail> linkedSourceRefs =
        sourceRefReader.findLegacyDetailsByIds(workspaceId, sourceRefIds);

    Map<UUID, List<WorkspaceSnapshotResponse.SnapshotMemoryResponse>> memoriesByTaskId =
        new HashMap<>();
    Map<UUID, List<WorkspaceSnapshotResponse.SnapshotSourceRefResponse>> sourceRefsByTaskId =
        new HashMap<>();
    Map<UUID, Set<UUID>> taskIdsByMemoryId = new HashMap<>();
    Map<UUID, Set<UUID>> taskIdsBySourceRefId = new HashMap<>();
    linksByTaskId.forEach(
        (taskId, links) -> {
          memoriesByTaskId.put(taskId, new ArrayList<>());
          sourceRefsByTaskId.put(taskId, new ArrayList<>());
          links
              .memoryIds()
              .forEach(
                  memoryId ->
                      taskIdsByMemoryId
                          .computeIfAbsent(memoryId, ignored -> new LinkedHashSet<>())
                          .add(taskId));
          links
              .sourceRefIds()
              .forEach(
                  sourceRefId ->
                      taskIdsBySourceRefId
                          .computeIfAbsent(sourceRefId, ignored -> new LinkedHashSet<>())
                          .add(taskId));
        });
    linkedMemories.forEach(
        memory -> {
          WorkspaceSnapshotResponse.SnapshotMemoryResponse response =
              WorkspaceSnapshotResponse.SnapshotMemoryResponse.from(memory);
          taskIdsByMemoryId
              .getOrDefault(memory.id(), Set.of())
              .forEach(taskId -> memoriesByTaskId.get(taskId).add(response));
        });
    linkedSourceRefs.forEach(
        sourceRef -> {
          WorkspaceSnapshotResponse.SnapshotSourceRefResponse response =
              WorkspaceSnapshotResponse.SnapshotSourceRefResponse.from(sourceRef);
          taskIdsBySourceRefId
              .getOrDefault(sourceRef.id(), Set.of())
              .forEach(taskId -> sourceRefsByTaskId.get(taskId).add(response));
        });

    return WorkspaceSnapshotResponse.from(
        workspace,
        members,
        projects,
        milestones,
        tasks,
        blockers,
        candidates,
        memories,
        tasks.stream()
            .filter(task -> linksByTaskId.containsKey(task.id()))
            .map(
                task -> {
                  return new WorkspaceSnapshotResponse.SnapshotTaskContextResponse(
                      task.id(),
                      memoriesByTaskId.get(task.id()),
                      sourceRefsByTaskId.get(task.id()));
                })
            .toList());
  }
}
