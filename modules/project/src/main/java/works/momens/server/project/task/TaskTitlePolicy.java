package works.momens.server.project.task;

/**
 * 태스크 제목의 최대 길이를 정의합니다. 모바일, 웹, MCP의 요청 검증과 minsu의 제목 자르기 로직이 이 상수를 공통으로 참조하므로, 최대 길이를 변경할 때는 이 상수만
 * 수정하는 방식으로 대응합니다.
 */
public final class TaskTitlePolicy {

  public static final int MAX_LENGTH = 70;

  private TaskTitlePolicy() {}
}
