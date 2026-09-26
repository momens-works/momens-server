package works.momens.server.project.task;

import java.time.LocalDate;
import java.util.UUID;

/**
 * 태스크 생성 입력.
 *
 * <p>{@code workspaceId}는 호출 모듈에서 권한 검사 과정에 확정한 값을 전달합니다. 요청 값은 각 호출 모듈의 요청 DTO에서 enum 타입으로
 * validation하며, 이 모듈은 참조 유효성 검증과 저장, 라벨 발급을 책임집니다.
 *
 * <p>{@code origin}은 사람이 직접 만든 태스크({@link TaskOrigin#MANUAL})와 Signal을 수용해 만든 태스크({@link
 * TaskOrigin#SIGNAL})를 구분합니다(CO-6). {@code SIGNAL}은 {@code originSignalId}가 반드시 있어야 하고, {@code
 * MANUAL}은 반드시 없어야 합니다 — 이 불변식은 생성 시점에 강제하며 조용히 기본값으로 채우지 않습니다.
 */
public record CreateTaskCommand(
    UUID projectId,
    UUID workspaceId,
    String title,
    String description,
    TaskStatus status,
    TaskRole role,
    TaskPriority priority,
    UUID milestoneId,
    UUID assigneeId,
    LocalDate dueDate,
    TaskOrigin origin,
    UUID originSignalId) {

  public CreateTaskCommand {
    if (status == null) {
      throw new IllegalArgumentException("status는 필수입니다.");
    }
    if (origin == null) {
      throw new IllegalArgumentException("origin은 필수입니다.");
    }
    boolean hasSignalId = originSignalId != null;
    if (origin == TaskOrigin.SIGNAL && !hasSignalId) {
      throw new IllegalArgumentException("origin이 SIGNAL이면 originSignalId가 필요합니다.");
    }
    if (origin == TaskOrigin.MANUAL && hasSignalId) {
      throw new IllegalArgumentException("origin이 MANUAL이면 originSignalId를 가질 수 없습니다.");
    }
  }

  public static CreateTaskCommand manual(
      UUID projectId, UUID workspaceId, String title, TaskRole role, TaskPriority priority) {
    return new CreateTaskCommand(
        projectId,
        workspaceId,
        title,
        null,
        TaskStatus.TODO,
        role,
        priority,
        null,
        null,
        null,
        TaskOrigin.MANUAL,
        null);
  }

  public static CreateTaskCommand fromSignal(
      UUID projectId,
      UUID workspaceId,
      String title,
      TaskRole role,
      TaskPriority priority,
      UUID signalId) {
    return new CreateTaskCommand(
        projectId,
        workspaceId,
        title,
        null,
        TaskStatus.TODO,
        role,
        priority,
        null,
        null,
        null,
        TaskOrigin.SIGNAL,
        signalId);
  }
}
