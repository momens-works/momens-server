package works.momens.server.mobile.board.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import works.momens.server.mobile.MobileDraftStatus;
import works.momens.server.mobile.MobilePriority;
import works.momens.server.mobile.board.MobileTaskDetail;
import works.momens.server.mobile.board.MobileTaskDetailView;
import works.momens.server.project.task.TaskRole;
import works.momens.server.project.task.TaskStatus;

/**
 * {@code GET /api/mobile/tasks/{taskId}} 응답. 응답 형식은 docs/spec/mobile-api.md 태스크 상세 절을 따릅니다.
 *
 * <p>완료 수와 전체 수는 항목 목록에서 파생합니다(별도 저장 없음). {@code materials}는 태스크에 연결된 source_ref를 조합한 값이고, 연결된 자료가
 * 없으면 빈 배열입니다. {@code open_questions}와 {@code next_action}은 민수가 생산한 값을 그대로 내려줍니다(ADR-0011). 열린질문이
 * 없으면 빈 배열이고, 다음행동을 아직 만들지 않았으면 null입니다.
 *
 * <p>{@code draft_status}는 이 태스크의 제목을 AI가 더 손볼 것이 남았는지를 알립니다(MOM-0822). 앱이 결과를 확인하는 경로는 convert 응답과
 * 이 상세 둘뿐이고, 보드·목록에는 노출하지 않습니다(설계 7.2절).
 */
@Schema(description = "태스크 상세 응답")
public record TaskDetailResponse(
    @Schema(description = "태스크 식별자") UUID id,
    @Schema(description = "소속 project 식별자") UUID projectId,
    @Schema(description = "제목") String title,
    @Schema(description = "상태", example = "todo", implementation = TaskStatus.class) String status,
    @Schema(
            description = "역할. 웹에서 만든 태스크는 역할을 지정하지 않으면 null",
            example = "pm",
            nullable = true,
            implementation = TaskRole.class)
        String role,
    @Schema(description = "담당자. 미지정이면 null") AssigneeResponse assignee,
    @Schema(description = "우선순위", example = "medium", implementation = MobilePriority.class)
        String priority,
    @Schema(description = "목적. 작성 전이면 null") String purpose,
    @Schema(description = "완료기준") ChecklistResponse checklist,
    @Schema(description = "관련자료 목록. 연결된 자료가 없으면 빈 배열") List<MaterialResponse> materials,
    @Schema(description = "열린질문 목록. 민수가 만든 질문이 없으면 빈 배열") List<OpenQuestionResponse> openQuestions,
    @Schema(description = "다음행동. 민수가 아직 만들지 않았으면 null", nullable = true) String nextAction,
    @Schema(
            description =
                "AI가 제목을 더 손볼 것이 남았는지. generating이면 나중에 다시 조회해야 합니다."
                    + " ready면 정상 종료 기준으로 지금 title이 최종 값이며, 생성이 deadline을 넘겨 닫힌"
                    + " 경우에만 뒤늦은 반영으로 한 번 더 바뀔 수 있습니다.",
            allowableValues = {"generating", "ready"},
            example = "ready")
        String draftStatus) {

  @Schema(description = "담당자")
  public record AssigneeResponse(
      @Schema(description = "사용자 식별자") UUID id,
      @Schema(description = "이름") String name,
      @Schema(description = "구글 계정 프로필 이미지. 없으면 null") String avatarUrl) {}

  @Schema(description = "완료기준")
  public record ChecklistResponse(
      @Schema(description = "완료된 항목 수") int completedCount,
      @Schema(description = "전체 항목 수") int totalCount,
      @Schema(description = "항목 목록(저장 순서)") List<ChecklistItemResponse> items) {}

  @Schema(description = "완료기준 항목")
  public record ChecklistItemResponse(
      @Schema(description = "항목 식별자") UUID id,
      @Schema(description = "항목 제목") String title,
      @Schema(description = "완료 여부") boolean completed) {}

  @Schema(description = "관련자료")
  public record MaterialResponse(
      @Schema(description = "자료 식별자(source_ref id)") UUID id,
      @Schema(description = "제목", nullable = true) String title,
      @Schema(description = "요약", nullable = true) String summary,
      @Schema(
              description =
                  "원본 출처입니다. slack, github, figma, notion, file 중 하나이며, 화면에 표시하는 라벨은 앱이 생성합니다.",
              example = "figma")
          String source,
      @Schema(description = "원본이 생성된 시각", nullable = true) Instant occurredAt,
      @Schema(description = "원본 문서 링크", nullable = true) String sourceUrl) {}

  @Schema(description = "열린질문")
  public record OpenQuestionResponse(
      @Schema(description = "질문 식별자") UUID id, @Schema(description = "질문 본문") String body) {}

  public static TaskDetailResponse from(MobileTaskDetailView view) {
    MobileTaskDetail detail = view.detail();
    List<ChecklistItemResponse> items =
        detail.checklistItems().stream()
            .map(item -> new ChecklistItemResponse(item.id(), item.title(), item.completed()))
            .toList();
    int completedCount = (int) items.stream().filter(ChecklistItemResponse::completed).count();
    AssigneeResponse assignee = toAssignee(detail.assignee());
    ChecklistResponse checklist = new ChecklistResponse(completedCount, items.size(), items);

    List<MaterialResponse> materials =
        detail.materials().stream().map(TaskDetailResponse::toMaterial).toList();
    List<OpenQuestionResponse> openQuestions =
        detail.openQuestions().stream()
            .map(question -> new OpenQuestionResponse(question.id(), question.body()))
            .toList();

    return new TaskDetailResponse(
        detail.id(),
        detail.projectId(),
        detail.title(),
        detail.status(),
        detail.role(),
        assignee,
        detail.priority(),
        detail.purpose(),
        checklist,
        materials,
        openQuestions,
        detail.nextAction(),
        MobileDraftStatus.key(view.draftStatus()));
  }

  private static MaterialResponse toMaterial(MobileTaskDetail.Material material) {
    return new MaterialResponse(
        material.id(),
        material.title(),
        material.summary(),
        material.source(),
        material.occurredAt(),
        material.sourceUrl());
  }

  private static AssigneeResponse toAssignee(MobileTaskDetail.Assignee assignee) {
    if (assignee == null) {
      return null;
    }
    return new AssigneeResponse(assignee.id(), assignee.name(), assignee.avatarUrl());
  }
}
