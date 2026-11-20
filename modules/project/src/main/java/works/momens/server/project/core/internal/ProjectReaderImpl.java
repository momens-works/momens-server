package works.momens.server.project.core.internal;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import works.momens.server.project.core.ProjectReader;
import works.momens.server.project.core.ProjectSnapshot;

@Service
@RequiredArgsConstructor
class ProjectReaderImpl implements ProjectReader {

  private final ProjectRepository projectRepository;

  @Override
  @Transactional(readOnly = true)
  public Optional<UUID> workspaceIdOf(UUID projectId) {
    return projectRepository.findByIdAndDeletedAtIsNull(projectId).map(Project::getWorkspaceId);
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<UUID> lockWorkspaceIdOf(UUID projectId) {
    return projectRepository.findActiveWithLock(projectId).map(Project::getWorkspaceId);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<ProjectSnapshot> findSnapshot(UUID projectId) {
    return projectRepository
        .findByIdAndDeletedAtIsNull(projectId)
        .map(ProjectReaderImpl::toSnapshot);
  }

  @Override
  @Transactional(readOnly = true)
  public List<ProjectSnapshot> listByWorkspaceIds(Collection<UUID> workspaceIds) {
    if (workspaceIds.isEmpty()) {
      return List.of();
    }
    return projectRepository
        .findByWorkspaceIdInAndDeletedAtIsNullOrderByCreatedAtDesc(workspaceIds)
        .stream()
        .map(ProjectReaderImpl::toSnapshot)
        .toList();
  }

  private static ProjectSnapshot toSnapshot(Project project) {
    return new ProjectSnapshot(
        project.getId(),
        project.getWorkspaceId(),
        project.getName(),
        project.getTargetDate(),
        project.getSummary());
  }
}
