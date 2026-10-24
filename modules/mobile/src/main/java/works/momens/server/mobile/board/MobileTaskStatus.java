package works.momens.server.mobile.board;

import java.util.Arrays;
import java.util.List;
import works.momens.server.project.task.TaskStatus;

/** 선언 순서가 보드 표시 순서입니다. 수정 가능한 모든 상태를 포함해 backlog·cancelled로 바꾼 태스크도 보드에 남깁니다. */
public enum MobileTaskStatus {
  IN_PROGRESS(TaskStatus.IN_PROGRESS, "진행 중"),
  TODO(TaskStatus.TODO, "할 일"),
  DONE(TaskStatus.DONE, "완료"),
  CANCELLED(TaskStatus.CANCELLED, "취소됨"),
  BACKLOG(TaskStatus.BACKLOG, "백로그");

  private final TaskStatus taskStatus;
  private final String label;

  MobileTaskStatus(TaskStatus taskStatus, String label) {
    this.taskStatus = taskStatus;
    this.label = label;
  }

  /**
   * 태스크의 status에 해당하는 보드 그룹을 반환합니다. `TaskStatus`에 값이 추가되면 이 switch에서 컴파일 오류가 발생해 보드 그룹 누락을 확인할 수
   * 있습니다.
   */
  public static MobileTaskStatus of(TaskStatus status) {
    return switch (status) {
      case TODO -> TODO;
      case IN_PROGRESS -> IN_PROGRESS;
      case DONE -> DONE;
      case BACKLOG -> BACKLOG;
      case CANCELLED -> CANCELLED;
    };
  }

  /** 도메인 상태 문자열. 응답의 group_key이자 project 조회 필터로 넘기는 값입니다. */
  public String key() {
    return taskStatus.value();
  }

  public String label() {
    return label;
  }

  public static List<String> keys() {
    return Arrays.stream(values()).map(MobileTaskStatus::key).toList();
  }
}
