package works.momens.server.project.task;

import java.util.UUID;

/**
 * 태스크의 제약 조건과 권한 규칙을 정의합니다. 제목의 최대 길이와 태스크 삭제 및 복구 권한을 관리합니다.
 *
 * <p>MCP 도구 스키마는 JSON 리소스로 관리하므로 상수를 직접 참조할 수 없습니다. 대신 스키마에 정의된 최대 길이가 이 상수와 일치하는지 테스트로 검증합니다.
 */
public final class TaskPolicy {

  public static final int TITLE_MAX_LENGTH = 70;

  private TaskPolicy() {}

  /** 요청한 사용자의 태스크 삭제 및 복구 권한을 확인합니다. 태스크 작성자만 허용하며, 작성자 정보가 없는 태스크는 누구도 삭제하거나 복구할 수 없습니다. */
  public static boolean canDelete(UUID creatorId, UUID userId) {
    return creatorId != null && creatorId.equals(userId);
  }

  static void requireTitleWithinLimit(String title) {
    if (title != null && title.length() > TITLE_MAX_LENGTH) {
      throw new IllegalArgumentException("title은 " + TITLE_MAX_LENGTH + "자를 초과할 수 없습니다.");
    }
  }
}
