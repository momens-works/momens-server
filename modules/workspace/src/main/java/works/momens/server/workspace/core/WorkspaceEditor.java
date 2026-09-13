package works.momens.server.workspace.core;

/**
 * 워크스페이스를 수정하는 public API.
 *
 * <p>slug의 형식, 예약어 해당 여부, 중복 여부를 이 API에서 검증하며, 규칙을 위반하면 workspace 도메인 에러를 던집니다. 워크스페이스가 없는 경우와 권한이
 * 부족한 경우를 어떻게 구분할지는 호출 측에서 결정합니다.
 */
public interface WorkspaceEditor {

  /** 워크스페이스를 수정하고 변경된 상태를 반환합니다. */
  WorkspaceDetail update(UpdateWorkspaceCommand command);
}
