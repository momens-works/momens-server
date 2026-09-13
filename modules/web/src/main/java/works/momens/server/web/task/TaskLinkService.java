package works.momens.server.web.task;

import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import works.momens.server.common.api.BusinessException;
import works.momens.server.context.ContextErrorCode;
import works.momens.server.context.EntityRelationCommand;
import works.momens.server.context.EntityRelationWriter;
import works.momens.server.context.EntityType;
import works.momens.server.context.RelationType;
import works.momens.server.memory.ConfirmedMemoryReader;
import works.momens.server.memory.MemoryErrorCode;
import works.momens.server.project.task.TaskErrorCode;
import works.momens.server.project.task.TaskReader;
import works.momens.server.source.SourceRefWriter;
import works.momens.server.web.WorkspaceAccessChecker;
import works.momens.server.workspace.membership.WorkspaceRole;

/**
 * 태스크에 연결되는 엔티티의 연결과 해제를 조합하는 서비스입니다.
 *
 * <p>연결은 {@code context} 모듈이 소유하고 source-ref 저장은 {@code source} 모듈이 소유합니다. 해당 서비스는 두 모듈의 public
 * API를 순서대로 호출하고 그 전에 필요한 검사를 수행합니다. 검사 순서는 레거시 {@code relation/service.go}와 같습니다. 태스크가 속한 워크스페이스를
 * 먼저 조회하고, 메모리를 연결할 때는 두 대상이 같은 워크스페이스에 속하는지 확인한 뒤 요청자가 해당 워크스페이스의 멤버인지 검사합니다.
 *
 * <p>태스크의 워크스페이스는 {@code TaskReader.findSnapshot}으로 조회합니다. {@code findScope}는 {@code
 * tasks.workspace_id}만 읽고 프로젝트의 소프트 삭제 여부를 확인하지 않아 레거시와 다른 값을 반환할 수 있습니다. 레거시는 프로젝트를 조인하고 태스크와
 * 프로젝트의 소프트 삭제 여부를 모두 확인합니다.
 *
 * <p>source-ref 생성과 연결은 하나의 트랜잭션으로 처리합니다. 레거시는 두 SQL 문을 트랜잭션 없이 실행하므로 연결 생성에 실패하면 어디에도 연결되지 않은
 * source-ref가 남을 수 있습니다.
 */
@Service
@RequiredArgsConstructor
class TaskLinkService {

  private final TaskReader taskReader;
  private final ConfirmedMemoryReader confirmedMemoryReader;
  private final SourceRefWriter sourceRefWriter;
  private final EntityRelationWriter entityRelationWriter;
  private final WorkspaceAccessChecker workspaceAccessChecker;

  @Transactional
  public void linkMemory(UUID taskId, UUID memoryId, UUID userId) {
    UUID workspaceId = requireTaskWorkspace(taskId);
    UUID memoryWorkspaceId =
        confirmedMemoryReader
            .findWorkspaceId(memoryId)
            .orElseThrow(
                () ->
                    new BusinessException(
                        MemoryErrorCode.MEMORY_NOT_FOUND,
                        Map.of("memory_id", memoryId.toString())));
    requireSameWorkspace(workspaceId, memoryWorkspaceId);
    requireMember(workspaceId, userId);
    entityRelationWriter.link(command(workspaceId, taskId, EntityType.MEMORY, memoryId));
  }

  @Transactional
  public void unlinkMemory(UUID taskId, UUID memoryId, UUID userId) {
    UUID workspaceId = requireTaskWorkspace(taskId);
    requireMember(workspaceId, userId);
    requireUnlinked(
        entityRelationWriter.unlink(command(workspaceId, taskId, EntityType.MEMORY, memoryId)));
  }

  @Transactional
  public UUID createSourceRef(
      UUID taskId, UUID userId, String sourceUrl, String sourceType, String title) {
    UUID workspaceId = requireTaskWorkspace(taskId);
    requireMember(workspaceId, userId);
    UUID sourceRefId =
        sourceRefWriter.createManualLink(
            new SourceRefWriter.NewManualLink(workspaceId, sourceType, sourceUrl, title));
    entityRelationWriter.link(command(workspaceId, taskId, EntityType.SOURCE_OBJECT, sourceRefId));
    return sourceRefId;
  }

  @Transactional
  public void unlinkSourceRef(UUID taskId, UUID sourceRefId, UUID userId) {
    UUID workspaceId = requireTaskWorkspace(taskId);
    requireMember(workspaceId, userId);
    requireUnlinked(
        entityRelationWriter.unlink(
            command(workspaceId, taskId, EntityType.SOURCE_OBJECT, sourceRefId)));
  }

  private UUID requireTaskWorkspace(UUID taskId) {
    return taskReader
        .findSnapshot(taskId)
        .orElseThrow(
            () ->
                new BusinessException(
                    TaskErrorCode.TASK_NOT_FOUND, Map.of("task_id", taskId.toString())))
        .workspaceId();
  }

  private void requireSameWorkspace(UUID taskWorkspaceId, UUID targetWorkspaceId) {
    if (!taskWorkspaceId.equals(targetWorkspaceId)) {
      throw new BusinessException(ContextErrorCode.CONTEXT_CROSS_WORKSPACE_LINK_NOT_ALLOWED);
    }
  }

  private void requireMember(UUID workspaceId, UUID userId) {
    workspaceAccessChecker.requireRoleAtLeast(workspaceId, userId, WorkspaceRole.MEMBER);
  }

  private void requireUnlinked(boolean unlinked) {
    if (!unlinked) {
      throw new BusinessException(ContextErrorCode.CONTEXT_LINK_NOT_FOUND);
    }
  }

  private static EntityRelationCommand command(
      UUID workspaceId, UUID taskId, EntityType toEntityType, UUID toEntityId) {
    return new EntityRelationCommand(
        workspaceId, EntityType.TASK, taskId, RelationType.LINKED_TO, toEntityType, toEntityId);
  }
}
