package works.momens.server.workspace.core;

/**
 * slug 사용 가능 여부의 판정 결과.
 *
 * <p>레거시 {@code SlugAvailability}와 필드가 1:1로 대응합니다. {@code reason}은 slug를 사용할 수 없는 경우에만 값이 있으며,
 * {@code suggestion}은 이미 사용 중인 경우에만 값이 있습니다. 두 필드를 응답에서 생략할지는 호출 측 DTO가 결정합니다.
 */
public record WorkspaceSlugAvailability(
    String slug, boolean available, Reason reason, String suggestion) {

  /**
   * slug를 사용할 수 없는 사유.
   *
   * <p>저장하는 값이 아니라 응답으로 전달하는 값이므로 레거시에서 사용하던 세 가지 문자열을 그대로 유지합니다. 각 사유를 화면에 어떻게 표시할지는 클라이언트가 결정합니다.
   */
  public enum Reason {
    INVALID("invalid"),
    RESERVED("reserved"),
    TAKEN("taken");

    private final String value;

    Reason(String value) {
      this.value = value;
    }

    public String value() {
      return value;
    }
  }

  public static WorkspaceSlugAvailability available(String slug) {
    return new WorkspaceSlugAvailability(slug, true, null, null);
  }

  public static WorkspaceSlugAvailability rejected(String slug, Reason reason) {
    return new WorkspaceSlugAvailability(slug, false, reason, null);
  }

  public static WorkspaceSlugAvailability taken(String slug, String suggestion) {
    return new WorkspaceSlugAvailability(slug, false, Reason.TAKEN, suggestion);
  }
}
