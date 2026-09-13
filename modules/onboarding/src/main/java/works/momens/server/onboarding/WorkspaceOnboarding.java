package works.momens.server.onboarding;

import works.momens.server.workspace.core.CreateWorkspaceCommand;
import works.momens.server.workspace.core.WorkspaceDetail;

/**
 * 워크스페이스와 초기 데이터를 함께 저장하는 public API입니다.
 *
 * <p>레거시 {@code POST /workspaces}(H019)와 같은 범위를 담당합니다. {@code workspace} 모듈의 {@link
 * works.momens.server.workspace.core.WorkspaceCreator}는 {@code workspaces} 행과 owner 멤버십만 저장합니다. 해당
 * API는 이름이 {@code Welcome}인 프로젝트와 메모리 세 건을 추가하고 전체 저장 작업을 하나의 트랜잭션으로 묶습니다.
 *
 * <p>호출 지점은 {@code web} 모듈의 컨트롤러 하나입니다. 요청자의 권한은 확인하지 않습니다. 워크스페이스 생성은 인증만 요구하며, 생성 전에는 권한을 확인할
 * 워크스페이스가 존재하지 않습니다.
 */
public interface WorkspaceOnboarding {

  /** 워크스페이스와 초기 데이터를 하나의 트랜잭션으로 저장하고 웹 응답에 필요한 워크스페이스 필드를 반환합니다. */
  WorkspaceDetail createWorkspace(CreateWorkspaceCommand command);
}
