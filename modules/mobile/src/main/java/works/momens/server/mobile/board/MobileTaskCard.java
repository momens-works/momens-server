package works.momens.server.mobile.board;

import java.time.LocalDate;
import java.util.UUID;

/**
 * 보드 카드 한 장. {@code priority}는 모바일 응답용으로 urgent를 high로 바꾼 값이다.
 *
 * <p>{@code assignee}는 담당자가 지정되지 않은 경우 {@code null}이며, {@code dueDate}는 마감일이 지정되지 않은 경우 {@code
 * null}입니다.
 */
public record MobileTaskCard(
    UUID id,
    String title,
    String role,
    Assignee assignee,
    String priority,
    LocalDate dueDate,
    int materialCount) {

  /** 카드에 표시할 담당자 정보입니다. 카드에는 담당자 이름만 표시하므로 식별자와 이름만 포함합니다. */
  public record Assignee(UUID id, String name) {}
}
