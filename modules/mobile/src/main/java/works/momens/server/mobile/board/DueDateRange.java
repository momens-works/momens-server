package works.momens.server.mobile.board;

import java.time.LocalDate;
import works.momens.server.common.api.FieldValidationException;

/**
 * 모바일 태스크 보드의 마감일 필터에서 사용하는 조회 기간입니다.
 *
 * <p>조회 기간에는 시작일과 종료일이 모두 포함됩니다. 시작일 또는 종료일이 {@code null}이면 해당 날짜를 기준으로 조회 범위를 제한하지 않습니다. 시작일이
 * 종료일보다 늦으면 {@code COMMON_VALIDATION_FAILED}를 반환하고, 오류 필드는 {@code due_date_from}으로 지정합니다.
 *
 * <p>마감일이 지정되지 않은 태스크는 어떤 조회 기간에도 포함되지 않습니다.
 */
record DueDateRange(LocalDate from, LocalDate to) {

  static final String DUE_DATE_FROM = "due_date_from";
  static final String DUE_DATE_TO = "due_date_to";

  DueDateRange {
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
