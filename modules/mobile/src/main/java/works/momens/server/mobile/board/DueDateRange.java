package works.momens.server.mobile.board;

import java.time.LocalDate;
import works.momens.server.common.api.FieldValidationException;

/**
 * 모바일 태스크 보드의 마감일 필터에서 사용하는 조회 기간입니다.
 *
 * <p>조회 기간에는 시작일과 종료일이 모두 포함됩니다. 시작일이 {@code null}이면 조회 범위의 하한을 제한하지 않고, 종료일이 {@code null}이면 상한을
 * 제한하지 않습니다. 시작일과 종료일이 모두 {@code null}이면 {@link IllegalArgumentException}을 던집니다. 마감일 필터를 적용하지 않는
 * 경우에는 조회 기간 대신 {@code null}을 사용합니다. 시작일이 종료일보다 늦으면 오류 필드를 {@code due_date_from}으로 지정하여 {@link
 * FieldValidationException}을 던지며, 해당 예외는 {@code COMMON_VALIDATION_FAILED} 오류 응답으로 변환됩니다.
 *
 * <p>마감일이 지정되지 않은 태스크는 어떤 조회 기간에도 포함되지 않습니다.
 */
record DueDateRange(LocalDate from, LocalDate to) {

  static final String DUE_DATE_FROM = "due_date_from";
  static final String DUE_DATE_TO = "due_date_to";

  DueDateRange {
    if (from == null && to == null) {
      throw new IllegalArgumentException("조회 기간의 시작일과 종료일 중 하나 이상을 지정해야 합니다.");
    }
    if (from != null && to != null && from.isAfter(to)) {
      throw FieldValidationException.forField(DUE_DATE_FROM);
    }
  }

  boolean contains(LocalDate dueDate) {
    if (dueDate == null) {
      return false;
    }
    return (from == null || !dueDate.isBefore(from)) && (to == null || !dueDate.isAfter(to));
  }
}
