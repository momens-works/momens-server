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
 * 레거시 {@code workspace.Create}와 {@code seedWelcome}이 하나의 트랜잭션에서 수행하던 동작을 이관한 구현입니다.
 *
 * <p>워크스페이스, owner 멤버십, 이름이 {@code Welcome}인 프로젝트, 메모리 세 건 순으로 저장합니다. {@code
 * workspace_label_sequences}와 {@code projects}가 {@code workspaces}를 참조하므로 워크스페이스 행을 먼저 저장해야 합니다.
 * 프로젝트 생성 과정에서는 소유자가 워크스페이스 멤버인지 검증하므로 owner 멤버십도 프로젝트보다 먼저 저장해야 합니다.
 *
 * <p>트랜잭션은 해당 구현에서 시작합니다. 세 모듈의 저장 작업이 모두 같은 트랜잭션에 참여하므로 하나라도 실패하면 워크스페이스 생성까지 롤백됩니다. 레거시도 전체 저장
 * 작업을 하나의 트랜잭션으로 처리합니다.
 *
 * <p>프로젝트 이름과 설명, 메모리 세 건의 타입·제목·본문은 레거시 값을 변경 없이 사용합니다. 프로젝트 설명의 {@code U+00B7} 문자와 세 번째 메모리 본문의
 * {@code U+2014} 문자도 유지합니다. 두 서버가 같은 행을 생성해야 하므로 문자 단위의 차이도 허용하지 않습니다.
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
