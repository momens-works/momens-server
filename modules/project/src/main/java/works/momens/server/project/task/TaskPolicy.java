package works.momens.server.project.task;

/**
 * 태스크 필드의 제약 조건을 정의합니다. 현재는 제목의 최대 길이만 관리하며, 모바일과 웹의 요청 validation, 태스크 쓰기 command, minsu의 제목 자르기
 * 로직과 프롬프트에서 이 상수를 참조합니다.
 *
 * <p>MCP 도구 스키마는 JSON 리소스이므로 상수를 직접 참조할 수 없습니다. 대신 스키마에 선언된 최대 길이가 이 상수와 일치하는지 테스트로 검증합니다.
 *
 * <p>설명과 완료 기준의 길이 제한은 현재 모바일 수정 요청에만 적용되어 있어 공통 상수로 분리하지 않았습니다. 추후 웹이나 MCP에서도 동일한 제한이 필요해지면 이 클래스에
 * 상수를 추가할 예정입니다.
 */
public final class TaskPolicy {

  public static final int TITLE_MAX_LENGTH = 70;

  private TaskPolicy() {}

  public static void requireTitleWithinLimit(String title) {
    if (title != null && title.length() > TITLE_MAX_LENGTH) {
      throw new IllegalArgumentException("title은 " + TITLE_MAX_LENGTH + "자를 초과할 수 없습니다.");
    }
  }
}
