package works.momens.server.project.task;

/**
 * 태스크 필드의 제약 조건을 정의합니다. 현재는 제목의 최대 길이만 관리하며, 모바일 요청 validation과 minsu의 제목 자르기 로직 및 프롬프트에서 이 상수를
 * 참조합니다.
 *
 * <p>설명과 완료 기준의 길이 제한은 현재 모바일 수정 요청에만 적용되어 있어 공통 상수로 분리하지 않았습니다. 추후 웹이나 MCP에서도 동일한 제한이 필요해지면 이 클래스에
 * 상수를 추가할 예정입니다.
 */
public final class TaskPolicy {

  public static final int TITLE_MAX_LENGTH = 70;

  private TaskPolicy() {}
}
