package works.momens.server.workspace.core.internal;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import works.momens.server.workspace.core.WorkspaceSlugAvailability;
import works.momens.server.workspace.core.WorkspaceSlugReader;

@Service
@RequiredArgsConstructor
class WorkspaceSlugReaderImpl implements WorkspaceSlugReader {

  private final WorkspaceRepository workspaceRepository;

  @Override
  @Transactional(readOnly = true)
  public WorkspaceSlugAvailability availabilityOf(String rawSlug) {
    String slug = WorkspaceSlugPolicy.normalize(rawSlug);
    if (!WorkspaceSlugPolicy.isValid(slug)) {
      return WorkspaceSlugAvailability.rejected(slug, WorkspaceSlugAvailability.Reason.INVALID);
    }
    if (WorkspaceSlugPolicy.isReserved(slug)) {
      return WorkspaceSlugAvailability.rejected(slug, WorkspaceSlugAvailability.Reason.RESERVED);
    }
    if (workspaceRepository.existsBySlug(slug)) {
      return WorkspaceSlugAvailability.taken(
          slug, WorkspaceSlugPolicy.nextAvailable(slug, workspaceRepository::existsBySlug));
    }
    return WorkspaceSlugAvailability.available(slug);
  }
}
