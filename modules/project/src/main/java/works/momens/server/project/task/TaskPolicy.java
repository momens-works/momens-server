package works.momens.server.project.task;

/**
 * 태스크 필드의 제약 조건을 정의합니다. 현재는 제목의 최대 길이만 관리합니다.
 *
 * <p>MCP 도구 스키마는 JSON 리소스로 관리하므로 상수를 직접 참조할 수 없습니다. 대신 스키마에 정의된 최대 길이가 이 상수와 일치하는지 테스트로 검증합니다.
 */
public final class TaskPolicy {

  public static final int TITLE_MAX_LENGTH = 70;

  private TaskPolicy() {}

  static void requireTitleWithinLimit(String title) {
    if (title != null && title.length() > TITLE_MAX_LENGTH) {
      throw new IllegalArgumentException("title은 " + TITLE_MAX_LENGTH + "자를 초과할 수 없습니다.");
    }
  }
}
