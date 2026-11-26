package works.momens.server.mobile.board;

import java.time.LocalDate;
import works.momens.server.common.api.FieldValidationException;

/** 모바일 태스크 보드의 마감일 필터에서 사용하는 조회 기간입니다. 마감일 필터를 적용하지 않으면 조회 기간 대신 {@code null}을 사용합니다. */
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
