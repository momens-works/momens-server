package works.momens.server.onboarding.internal;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import works.momens.server.memory.ConfirmedMemoryCreator;
import works.momens.server.memory.CreateConfirmedMemoryCommand;
import works.momens.server.onboarding.WorkspaceOnboarding;
import works.momens.server.project.core.CreateProjectCommand;
import works.momens.server.project.core.ProjectCreator;
import works.momens.server.project.core.ProjectDetail;
import works.momens.server.workspace.core.CreateWorkspaceCommand;
import works.momens.server.workspace.core.WorkspaceCreator;
import works.momens.server.workspace.core.WorkspaceDetail;

/**
 * 워크스페이스와 owner 멤버십을 먼저 저장해야 프로젝트의 FK·멤버십 검증을 통과합니다.
 *
 * <p>모든 저장은 같은 트랜잭션에 참여합니다. 초기 데이터 문자열은 레거시 seed와 문자 단위로 일치해야 합니다.
 */
@Service
@RequiredArgsConstructor
class WorkspaceOnboardingImpl implements WorkspaceOnboarding {

  private static final String WELCOME_PROJECT_NAME = "Welcome";
  private static final String WELCOME_PROJECT_DESCRIPTION =
      "Default project · created with your workspace";
  private static final Map<String, Object> SEEDED_METADATA = Map.of("seeded", true);

  private static final List<StarterMemory> STARTER_MEMORIES =
      List.of(
          new StarterMemory(
              "DECISION",
              "This is what a captured decision looks like",
              "Momens keeps every decision traceable to the source it came from. Clear this once"
                  + " your first real one lands."),
          new StarterMemory(
              "OPEN_QUESTION",
              "Open questions stay visible until they resolve",
              "Unanswered context surfaces here so it never gets lost between tools."),
          new StarterMemory(
              "INSIGHT",
              "Add your own context any time",
              "A note is the simplest memory — a thought you want the team to keep."));

  private final WorkspaceCreator workspaceCreator;
  private final ProjectCreator projectCreator;
  private final ConfirmedMemoryCreator confirmedMemoryCreator;

  @Override
  @Transactional
  public WorkspaceDetail createWorkspace(CreateWorkspaceCommand command) {
    WorkspaceDetail workspace = workspaceCreator.create(command);
    ProjectDetail welcomeProject = createWelcomeProject(workspace.id(), command.requesterId());
    STARTER_MEMORIES.forEach(
        memory ->
            confirmedMemoryCreator.create(
                new CreateConfirmedMemoryCommand(
                    workspace.id(),
                    command.requesterId(),
                    memory.memoryType(),
                    memory.title(),
                    memory.body(),
                    List.of(welcomeProject.id()),
                    SEEDED_METADATA)));
    return workspace;
  }

  private ProjectDetail createWelcomeProject(UUID workspaceId, UUID requesterId) {
    return projectCreator.create(
        new CreateProjectCommand(
            workspaceId,
            requesterId,
            WELCOME_PROJECT_NAME,
            WELCOME_PROJECT_DESCRIPTION,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            List.of(requesterId),
            SEEDED_METADATA));
  }

  private record StarterMemory(String memoryType, String title, String body) {}
}
