package works.momens.server.project.task;

/**
 * 태스크 제목의 최대 길이를 정의합니다. 모바일과 웹의 요청 검증, 태스크 쓰기 command, minsu의 제목 자르기 로직과 프롬프트에서 이 상수를 참조합니다.
 *
 * <p>MCP 도구 스키마는 JSON 리소스이므로 상수를 직접 참조할 수 없습니다. 대신 스키마에 선언된 최대 길이가 이 상수와 일치하는지 테스트로 검증합니다.
 */
public final class TaskTitlePolicy {

  public static final int MAX_LENGTH = 70;

  private TaskTitlePolicy() {}
}
