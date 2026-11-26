package works.momens.server.mobile.board;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import works.momens.server.project.task.TaskDetail;

/**
 * 모바일 태스크 상세 조합 결과.
 *
 * <p>{@code purpose}는 도메인의 description을 화면 언어로 개명한 값입니다(와이어프레임 task_002). 완료기준 항목은 모바일 규칙 없이 그대로
 * 내려가므로 project public API 타입({@link TaskDetail.ChecklistItem})을 재사용합니다.
 *
 * <p>{@code materials}는 context의 연결과 source의 원본을 조합한 결과입니다.
 *
 * <p>{@code openQuestions}와 {@code nextAction}은 민수 산출물이라 모바일이 매핑 없이 그대로 내려주므로, 완료기준 항목과 같이 project
 * public API 타입({@link TaskDetail.OpenQuestion})을 재사용합니다.
 */
public record MobileTaskDetail(
    UUID id,
    UUID projectId,
    String title,
    String status,
    List<String> roles,
    Assignee assignee,
    String priority,
    LocalDate dueDate,
    String purpose,
    List<TaskDetail.ChecklistItem> checklistItems,
    List<Material> materials,
    List<TaskDetail.OpenQuestion> openQuestions,
    String nextAction,
    boolean canDelete,
    long version) {

  public record Assignee(UUID id, String name, String avatarUrl) {}

  /** 태스크 관련자료 한 건입니다. source_ref에서 조합하는 규칙은 Signal evidence와 같아야 합니다. */
  public record Material(
      UUID id, String title, String summary, String source, Instant occurredAt, String sourceUrl) {}
}
