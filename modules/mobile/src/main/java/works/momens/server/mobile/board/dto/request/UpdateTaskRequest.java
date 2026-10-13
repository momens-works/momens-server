package works.momens.server.mobile.board.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import works.momens.server.project.task.TaskPriority;
import works.momens.server.project.task.TaskRole;
import works.momens.server.project.task.TaskStatus;

/** 편집 상태 전체를 저장하므로 생략한 선택 필드는 삭제됩니다. 생성과 달리 빈 제목을 허용해 앱에서 새 작업으로 표시합니다. */
@Schema(description = "작업 수정 요청")
public record UpdateTaskRequest(
    @Schema(description = "제목. 값은 있어야 하고 빈 문자열은 허용합니다. 최대 15자(공백 포함).", example = "1차 와이어프레임")
        @NotNull
        @Size(max = 15)
        String title,
    @Schema(
            description =
                "업무 역할. pm은 기획과 조율, design은 디자인, backend는 서버, frontend는 웹과 앱 화면 작업을 담당합니다.",
            example = "pm")
        @NotNull
        TaskRole role,
    @Schema(description = "담당자 식별자. 생략하거나 null로 보내면 기존 담당자를 해제합니다.") UUID assigneeId,
    @Schema(description = "우선순위. urgent도 그대로 저장하며 응답에서는 high로 표시합니다.", example = "medium") @NotNull
        TaskPriority priority,
    @Schema(
            description =
                "상태. backlog는 아직 일정을 잡지 않은 작업, todo는 할 일, in_progress는 진행 중인 작업, done은 완료한 작업, cancelled는 취소한 작업입니다.",
            example = "in_progress")
        @NotNull
        TaskStatus status,
    @Schema(description = "설명. 생략하거나 null로 보내면 기존 설명을 제거합니다. 빈 문자열도 허용합니다. 최대 300자(공백 포함).")
        @Size(max = 300)
        String purpose,
    @Schema(
            description =
                "완료 기준 최종 목록으로 전체 교체합니다. 생략하거나 null로 보내면 빈 목록으로 처리해 기존 항목을 모두 삭제합니다. 0개에서 5개까지 허용합니다.")
        @Size(max = 5)
        @Valid
        List<ChecklistItemRequest> checklistItems) {

  public UpdateTaskRequest {
    // 수정 화면은 완료기준을 항상 보내지만, 필드가 빠져도 빈 목록으로 다뤄 전체 교체가 안전하게 동작하도록 한다.
    checklistItems = checklistItems == null ? List.of() : checklistItems;
  }

  /** 완료기준 한 항목. id가 있으면 기존 항목, 없으면 새 항목이고, completed는 수정 화면이 저장한 완료 상태입니다. */
  @Schema(description = "완료 기준 항목")
  public record ChecklistItemRequest(
      @Schema(description = "항목 식별자. 새 항목이면 생략합니다.") UUID id,
      @Schema(description = "항목 제목. 최대 50자(공백 포함).", example = "회원가입 에러 메시지 반영")
          @NotBlank
          @Size(max = 50)
          String title,
      @Schema(description = "완료 여부. 없으면 false입니다.", example = "false") Boolean completed) {

    public ChecklistItemRequest {
      // 수정 화면은 항상 completed를 보내지만, 필드가 빠져도 미완료로 다뤄 완료 상태 저장이 안전하게 동작하도록 한다.
      completed = completed == null ? Boolean.FALSE : completed;
    }
  }
}
