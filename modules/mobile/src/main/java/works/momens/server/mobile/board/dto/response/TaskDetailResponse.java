package works.momens.server.mobile.board.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import works.momens.server.mobile.MobileDraftStatus;
import works.momens.server.mobile.board.MobileTaskDetail;
import works.momens.server.mobile.board.MobileTaskDetailView;

@Schema(description = "작업 상세 응답")
public record TaskDetailResponse(
    @Schema(description = "작업 식별자") UUID id,
    @Schema(description = "소속 project 식별자") UUID projectId,
    @Schema(description = "제목") String title,
    @Schema(description = "상태. backlog/todo/in_progress/done/cancelled", example = "todo")
        String status,
    @Schema(
            description =
                "role 목록입니다. `pm`, `design`, `backend`, `frontend` 순서로 정렬하며, role이 지정되지 않은 경우 빈 배열을 반환합니다.")
        List<String> roles,
    @Schema(
            description = "`roles`로 대체된 필드입니다. `roles`의 첫 번째 값을 반환하며, 지정된 role이 없으면 `null`을 반환합니다.",
            example = "pm",
            nullable = true,
            deprecated = true)
        String role,
    @Schema(description = "담당자. 미지정이면 null") @Nullable AssigneeResponse assignee,
    @Schema(description = "우선순위 (urgent/high/medium/low)", example = "medium") String priority,
    @Schema(description = "마감일. 미설정 시 null", example = "2026-09-25", nullable = true)
        LocalDate dueDate,
    @Schema(description = "설명. 작성 전이면 null", nullable = true) @Nullable String purpose,
    @Schema(description = "완료 기준") ChecklistResponse checklist,
    @Schema(description = "연결된 소스 목록. 연결된 소스가 없으면 빈 배열") List<MaterialResponse> materials,
    @Schema(description = "질문 목록. 민수가 만든 질문이 없으면 빈 배열") List<OpenQuestionResponse> openQuestions,
    @Schema(description = "다음 행동. 민수가 아직 만들지 않았으면 null", nullable = true) String nextAction,
    @Schema(
            description = MobileDraftStatus.DESCRIPTION,
            allowableValues = {"generating", "ready"},
            example = "ready")
        String draftStatus,
    @Schema(description = "요청한 사용자의 태스크 삭제 가능 여부. 작성자인 경우에만 true이며, 작성자 정보가 없는 태스크는 false입니다.")
        boolean canDelete,
    @Schema(description = "수정 충돌 확인용 버전", minimum = "0") long version) {

  @Schema(description = "담당자")
  public record AssigneeResponse(
      @Schema(description = "사용자 식별자") UUID id,
      @Schema(description = "이름") String name,
      @Schema(description = "구글 계정 프로필 이미지. 없으면 null", nullable = true)
          @Nullable String avatarUrl) {}

  @Schema(description = "완료 기준")
  public record ChecklistResponse(
      @Schema(description = "완료된 항목 수") int completedCount,
      @Schema(description = "전체 항목 수") int totalCount,
      @Schema(description = "항목 목록(저장 순서)") List<ChecklistItemResponse> items) {}

  @Schema(description = "완료 기준 항목")
  public record ChecklistItemResponse(
      @Schema(description = "항목 식별자") UUID id,
      @Schema(description = "항목 제목") String title,
      @Schema(description = "완료 여부") boolean completed) {}

  @Schema(description = "연결된 소스")
  public record MaterialResponse(
      @Schema(description = "소스 식별자(source_ref id)") UUID id,
      @Schema(description = "제목", nullable = true) String title,
      @Schema(description = "요약", nullable = true) String summary,
      @Schema(
              description = "원본 출처입니다(예: slack, github, figma, notion, file). 목록에 없는 값이 올 수 있습니다.",
              example = "figma")
          String source,
      @Schema(description = "원본이 생성된 시각", nullable = true) Instant occurredAt,
      @Schema(description = "원본 문서 링크", nullable = true) String sourceUrl) {}

  @Schema(description = "질문")
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
        detail.roles(),
        detail.roles().isEmpty() ? null : detail.roles().getFirst(),
        assignee,
        detail.priority(),
        detail.dueDate(),
        detail.purpose(),
        checklist,
        materials,
        openQuestions,
        detail.nextAction(),
        MobileDraftStatus.key(view.draftStatus()),
        detail.canDelete(),
        detail.version());
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
