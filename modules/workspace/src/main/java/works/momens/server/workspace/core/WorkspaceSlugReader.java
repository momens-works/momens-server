package works.momens.server.workspace.core;

/**
 * workspace slug의 사용 가능 여부를 조회하는 public API.
 *
 * <p>slug 규칙은 workspace 모듈이 소유하며, 생성(H019)과 수정(H024)에서도 동일한 규칙을 사용합니다.
 */
public interface WorkspaceSlugReader {

  /**
   * rawSlug의 사용 가능 여부를 판정합니다.
   *
   * <p>워크스페이스 이름으로부터 slug를 자동 생성하는 경로와 달리, 사용자가 직접 입력한 값은 보정하지 않습니다.
   */
  WorkspaceSlugAvailability availabilityOf(String rawSlug);
}
