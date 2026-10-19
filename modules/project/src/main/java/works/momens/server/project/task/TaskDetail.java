package works.momens.server.project.task;

import java.util.List;
import java.util.UUID;

/**
 * 상세 조회용 태스크 한 건.
 *
 * <p>{@code status}, {@code priority}, {@code description}은 저장된 원본 그대로입니다. 모바일 표기 매핑(urgent를 high로,
 * description을 purpose로)은 조회하는 쪽(mobile)이 정합니다({@link BoardTask}와 같은 원칙). {@code workspaceId}는 호출
 * 쪽이 태스크 접근 권한(멤버십)을 확인할 때 씁니다. {@code checklistItems}는 저장 순서 그대로입니다.
 *
 * <p>{@code openQuestions}와 {@code nextAction}은 민수가 생산하고 이 서버는 읽기만 하는 값입니다(ADR-0011). 저장된 값을 자르거나
 * 만들지 않고 그대로 담습니다. 열린질문이 없으면 빈 목록이고, 다음행동을 아직 만들지 않았으면 null입니다.
 */
public record TaskDetail(
    UUID id,
    UUID projectId,
    UUID workspaceId,
    String title,
    String status,
    String priority,
    String role,
    UUID assigneeId,
    String description,
    List<ChecklistItem> checklistItems,
    List<OpenQuestion> openQuestions,
    String nextAction,
    long version) {

  public record ChecklistItem(UUID id, String title, boolean completed) {}

  public record OpenQuestion(UUID id, String body) {}
}
