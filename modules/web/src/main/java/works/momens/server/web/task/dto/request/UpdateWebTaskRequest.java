package works.momens.server.web.task.dto.request;

import com.fasterxml.jackson.annotation.JsonSetter;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.UUID;
import works.momens.server.project.task.TaskPriority;
import works.momens.server.project.task.TaskStatus;

/** PATCH의 누락과 null을 구분하는 웹 task 요청입니다. */
@Schema(description = "웹 태스크 수정 요청")
public final class UpdateWebTaskRequest {
  @Schema(description = "태스크 제목. 빈 문자열은 기존 값을 유지하며 null은 허용하지 않습니다.")
  private String title;

  private boolean titleSet;

  @Schema(description = "태스크 설명. null이면 설명을 제거합니다.")
  private String description;

  private boolean descriptionSet;

  @Schema(
      description = "태스크 상태. 빈 문자열은 기존 값을 유지하며 null은 허용하지 않습니다.",
      implementation = TaskStatus.class)
  private String status;

  private boolean statusSet;

  @Schema(
      description = "태스크 우선순위. 빈 문자열은 기존 값을 유지하며 null은 허용하지 않습니다.",
      implementation = TaskPriority.class)
  private String priority;

  private boolean prioritySet;

  @Schema(description = "마일스톤 식별자. null이면 연결을 해제합니다.", format = "uuid")
  private UUID milestoneId;

  private boolean milestoneIdSet;

  @Schema(description = "담당자 식별자. null이면 담당자를 해제합니다.", format = "uuid")
  private UUID assigneeId;

  private boolean assigneeIdSet;

  @Schema(description = "마감일. null이면 마감일을 제거합니다.", format = "date")
  private LocalDate dueDate;

  private boolean dueDateSet;

  @JsonSetter("title")
  public void setTitle(String value) {
    title = value;
    titleSet = true;
  }

  @JsonSetter("description")
  public void setDescription(String value) {
    description = value;
    descriptionSet = true;
  }

  @JsonSetter("status")
  public void setStatus(String value) {
    status = value;
    statusSet = true;
  }

  @JsonSetter("priority")
  public void setPriority(String value) {
    priority = value;
    prioritySet = true;
  }

  @JsonSetter("milestone_id")
  public void setMilestoneId(UUID value) {
    milestoneId = value;
    milestoneIdSet = true;
  }

  @JsonSetter("assignee_id")
  public void setAssigneeId(UUID value) {
    assigneeId = value;
    assigneeIdSet = true;
  }

  @JsonSetter("due_date")
  public void setDueDate(LocalDate value) {
    dueDate = value;
    dueDateSet = true;
  }

  public String title() {
    return title;
  }

  @Schema(hidden = true)
  public boolean titleSet() {
    return titleSet;
  }

  public String description() {
    return description;
  }

  @Schema(hidden = true)
  public boolean descriptionSet() {
    return descriptionSet;
  }

  public String status() {
    return status;
  }

  @Schema(hidden = true)
  public boolean statusSet() {
    return statusSet;
  }

  public String priority() {
    return priority;
  }

  @Schema(hidden = true)
  public boolean prioritySet() {
    return prioritySet;
  }

  public UUID milestoneId() {
    return milestoneId;
  }

  @Schema(hidden = true)
  public boolean milestoneIdSet() {
    return milestoneIdSet;
  }

  public UUID assigneeId() {
    return assigneeId;
  }

  @Schema(hidden = true)
  public boolean assigneeIdSet() {
    return assigneeIdSet;
  }

  public LocalDate dueDate() {
    return dueDate;
  }

  @Schema(hidden = true)
  public boolean dueDateSet() {
    return dueDateSet;
  }
}
