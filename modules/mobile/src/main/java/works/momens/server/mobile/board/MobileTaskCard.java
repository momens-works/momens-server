package works.momens.server.mobile.board;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 모바일 보드의 태스크 카드입니다. {@code priority}에는 저장값을 변환 없이 사용합니다.
 *
 * <p>{@code assignee}는 담당자가 지정되지 않은 경우 {@code null}이며, {@code dueDate}는 마감일이 지정되지 않은 경우 {@code
 * null}입니다.
 */
public record MobileTaskCard(
    UUID id,
    String title,
    List<String> roles,
    Assignee assignee,
    String priority,
    LocalDate dueDate,
    int materialCount) {

  /** 카드에 표시할 담당자 정보입니다. 카드에는 담당자 이름만 표시하므로 식별자와 이름만 포함합니다. */
  public record Assignee(UUID id, String name) {}
}
