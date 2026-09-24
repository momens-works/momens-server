package works.momens.server.mobile.board;

import java.util.Arrays;
import java.util.List;
import works.momens.server.project.task.TaskStatus;

/**
 * 보드 상태.
 *
 * <p>enum 선언 순서가 보드 그룹의 노출 순서입니다. 사용자가 자주 확인하는 상태를 앞에 배치해 투두, 진행중, 완료, 백로그, 취소 순으로 노출합니다(2026-07-08
 * 화면설계서 task_001).
 *
 * <p>수정 화면은 상태 5종을 모두 편집할 수 있으며, backlog나 cancelled로 변경한 태스크도 보드에 계속 표시되어야 하므로 보드 역시 상태 5종을 모두
 * 포함합니다(MOM-75).
 */
public enum BoardStatus {
  TODO(TaskStatus.TODO, "투두"),
  IN_PROGRESS(TaskStatus.IN_PROGRESS, "진행중"),
  DONE(TaskStatus.DONE, "완료"),
  BACKLOG(TaskStatus.BACKLOG, "백로그"),
  CANCELLED(TaskStatus.CANCELLED, "취소");

  private final TaskStatus taskStatus;
  private final String label;

  BoardStatus(TaskStatus taskStatus, String label) {
    this.taskStatus = taskStatus;
    this.label = label;
  }

  /** 도메인 상태 문자열. 응답의 group_key이자 project 조회 필터로 넘기는 값입니다. */
  public String key() {
    return taskStatus.value();
  }

  /** 보드 상태에 대응하는 태스크 상태를 반환합니다. */
  public TaskStatus taskStatus() {
    return taskStatus;
  }

  /** 화면에 보이는 그룹 이름입니다. */
  public String label() {
    return label;
  }

  /** project 조회에 넘길 보드 상태 키 목록입니다. */
  public static List<String> keys() {
    return Arrays.stream(values()).map(BoardStatus::key).toList();
  }
}
