package works.momens.server.workspace.core;

/**
 * {@code workspace} 모듈의 워크스페이스 생성 public API입니다.
 *
 * <p>레거시 {@code POST /workspaces}가 하나의 트랜잭션에서 처리하던 작업 중 {@code workspaces} 행과 owner 멤버십 저장을 담당합니다.
 * 같은 트랜잭션에서 생성하는 {@code Welcome} 프로젝트와 메모리 세 건은 해당 모듈의 책임에 포함하지 않습니다.
 *
 * <p>{@code project}와 {@code memory} 모듈이 이미 {@code workspace} 모듈을 참조하므로 반대 방향으로 참조하면 순환 의존성이 발생합니다.
 * 세 모듈의 작업 순서와 트랜잭션 경계는 {@code onboarding} 모듈이 소유합니다.
 *
 * <p>요청자의 권한은 확인하지 않습니다. 워크스페이스 생성은 인증만 요구하며, 생성 전에는 권한을 확인할 워크스페이스가 존재하지 않습니다.
 */
public interface WorkspaceCreator {

  /** 워크스페이스와 owner 멤버십을 저장하고 웹 응답에 필요한 필드를 반환합니다. */
  WorkspaceDetail create(CreateWorkspaceCommand command);
}
