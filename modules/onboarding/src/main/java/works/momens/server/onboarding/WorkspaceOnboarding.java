package works.momens.server.onboarding;

import works.momens.server.workspace.core.CreateWorkspaceCommand;
import works.momens.server.workspace.core.WorkspaceDetail;

/**
 * 워크스페이스·owner 멤버십·초기 프로젝트와 메모리를 하나의 트랜잭션으로 생성합니다.
 *
 * <p>생성 전에는 권한을 확인할 워크스페이스가 없으므로 호출자는 인증만 확인합니다.
 */
public interface WorkspaceOnboarding {

  WorkspaceDetail createWorkspace(CreateWorkspaceCommand command);
}
