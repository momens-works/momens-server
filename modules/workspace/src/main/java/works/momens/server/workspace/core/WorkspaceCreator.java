package works.momens.server.workspace.core;

/**
 * 워크스페이스와 owner 멤버십을 함께 저장합니다. 생성 전에는 워크스페이스 권한이 없으므로 호출자는 인증만 확인합니다.
 *
 * <p>초기 프로젝트·메모리를 함께 생성하는 트랜잭션은 순환 의존을 피하도록 onboarding 모듈에서 조합합니다.
 */
public interface WorkspaceCreator {

  WorkspaceDetail create(CreateWorkspaceCommand command);
}
