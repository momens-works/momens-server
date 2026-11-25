package works.momens.server.mobile.board.dto.request;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.hibernate.validator.constraints.UniqueElements;
import org.openapitools.jackson.nullable.JsonNullable;
import tools.jackson.databind.annotation.JsonDeserialize;
import works.momens.server.common.api.ExpectedVersionDeserializer;
import works.momens.server.project.task.TaskPolicy;
import works.momens.server.project.task.TaskPriority;
import works.momens.server.project.task.TaskRole;
import works.momens.server.project.task.TaskStatus;

@Schema(description = "작업 수정 요청")
public record UpdateTaskRequest(
    @Schema(description = "태스크 제목. 빈 문자열을 허용합니다.", example = "1차 와이어프레임")
        @NotNull
        @Size(max = TaskPolicy.TITLE_MAX_LENGTH)
        String title,
    @ArraySchema(
            arraySchema =
                @Schema(description = "전달한 목록으로 기존 role 목록을 교체합니다. `role`을 보내지 않으면 필수입니다."),
            uniqueItems = true)
        @Size(min = 1)
        @UniqueElements
        List<@NotNull TaskRole> roles,
    @Schema(
            deprecated = true,
            description =
                "`roles`로 대체된 필드입니다. `roles` 없이 `role`만 보내면 기존 목록에 해당 role이 있을 때 목록을 유지하고, 없을 때 해당 role 하나로 교체합니다.",
            example = "pm")
        TaskRole role,
    @Schema(description = "담당자 식별자. 생략하거나 null로 보내면 기존 담당자를 해제합니다.", nullable = true)
        UUID assigneeId,
    @Schema(description = "우선순위", example = "medium") @NotNull TaskPriority priority,
    @Schema(
            description = "마감일. 생략하면 기존 마감일을 유지하고, null로 전달하면 삭제합니다.",
            example = "2026-09-25",
            nullable = true)
        JsonNullable<LocalDate> dueDate,
    @Schema(description = "상태", example = "in_progress") @NotNull TaskStatus status,
    @Schema(description = "설명. 생략하거나 null로 보내면 기존 설명을 제거합니다. 빈 문자열도 허용합니다.", nullable = true)
        @Size(max = 300)
        String purpose,
    @Schema(
            description =
                "완료 기준 최종 목록으로 전체 교체합니다. 생략하거나 null로 보내면 기존 항목을 모두 삭제합니다. 없는 항목 id는"
                    + " TASK_CHECKLIST_ITEM_NOT_FOUND, 중복 id는 COMMON_VALIDATION_FAILED입니다.",
            nullable = true)
        @Size(max = 5)
        @Valid
        List<ChecklistItemRequest> checklistItems,
    @Schema(
            description = "조회한 작업 버전. 생략하거나 null이면 요청 처리 중의 동시 변경만 검사합니다.",
            minimum = "0",
            nullable = true)
        @JsonDeserialize(using = ExpectedVersionDeserializer.class)
        Long version) {

  public UpdateTaskRequest {
    // 수정 화면은 완료기준을 항상 보내지만, 필드가 빠져도 빈 목록으로 다뤄 전체 교체가 안전하게 동작하도록 한다.
    checklistItems = checklistItems == null ? List.of() : checklistItems;
  }

  @Schema(description = "완료 기준 항목")
  public record ChecklistItemRequest(
      @Schema(description = "기존 항목 식별자. 새 항목이면 생략합니다.") UUID id,
      @Schema(description = "항목 제목. 빈 문자열이나 공백만으로 구성할 수 없습니다.", example = "회원가입 에러 메시지 반영")
          @NotBlank
          @Size(max = 50)
          String title,
      @Schema(description = "완료 여부", example = "false", defaultValue = "false") Boolean completed) {

    public ChecklistItemRequest {
      // 수정 화면은 항상 completed를 보내지만, 필드가 빠져도 미완료로 다뤄 완료 상태 저장이 안전하게 동작하도록 한다.
      completed = completed == null ? Boolean.FALSE : completed;
    }
  }
}
