package works.momens.server.mobile.board;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import works.momens.server.project.task.TaskDetail;

/**
 * 모바일 태스크 상세 조합 결과.
 *
 * <p>{@code purpose}는 도메인의 description을 화면 언어로 개명한 값이고(와이어프레임 task_002), {@code priority}는 모바일
 * 3종(low/medium/high)으로 매핑된 값입니다. 담당자가 없으면 {@code assignee}가 null입니다. 완료기준 항목은 모바일 규칙 없이 그대로 내려가므로
 * project public API 타입({@link TaskDetail.ChecklistItem})을 재사용합니다.
 *
 * <p>{@code materials}는 context의 연결과 source의 원본을 조합한 결과입니다. 연결된 자료가 없으면 빈 목록입니다.
 *
 * <p>{@code openQuestions}와 {@code nextAction}은 민수 산출물이라 모바일이 매핑 없이 그대로 내려주므로, 완료기준 항목과 같이 project
 * public API 타입({@link TaskDetail.OpenQuestion})을 재사용합니다. 열린질문이 없으면 빈 목록이고, 다음행동을 아직 만들지 않았으면
 * null입니다.
 */
public record MobileTaskDetail(
    UUID id,
    UUID projectId,
    String title,
    String status,
    String role,
    Assignee assignee,
    String priority,
    String purpose,
    List<TaskDetail.ChecklistItem> checklistItems,
    List<Material> materials,
    List<TaskDetail.OpenQuestion> openQuestions,
    String nextAction,
    long version) {

  /** 담당자 표시 정보. avatarUrl은 담당자의 구글 계정 프로필 이미지이고, 없으면 null입니다. */
  public record Assignee(UUID id, String name, String avatarUrl) {}

  /**
   * 태스크 관련자료 한 건입니다.
   *
   * <p>source_ref를 바탕으로 조합한 응답입니다. {@code source}는 원본 출처(figma 등)를 나타내며, {@code occurredAt}은 원본이
   * 생성된 시각입니다. 화면에 표시하는 출처 라벨은 클라이언트가 {@code source}에서 파생합니다. {@code summary}는 snippet이 없으면 text를
   * 대신 사용합니다. 이 규칙은 Signal evidence와 동일합니다.
   */
  public record Material(
      UUID id, String title, String summary, String source, Instant occurredAt, String sourceUrl) {}
}
