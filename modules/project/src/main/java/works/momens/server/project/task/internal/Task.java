package works.momens.server.project.task.internal;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import works.momens.server.common.persistence.BaseEntity;
import works.momens.server.project.task.CreateTaskCommand;
import works.momens.server.project.task.TaskDraftValues;
import works.momens.server.project.task.TaskPriority;
import works.momens.server.project.task.TaskRole;
import works.momens.server.project.task.TaskStatus;
import works.momens.server.project.task.UpdateTaskCommand;

/**
 * 태스크.
 *
 * <p>레거시 {@code momens-api}의 {@code tasks} 테이블과 호환됩니다. 모바일 보드/생성(MOM-62)이 읽고 쓰는 컬럼에 태스크 상세(MOM-63)가
 * 읽는 description, assignee_id를 더해 매핑하고, 모바일이 안 쓰는 레거시 컬럼(milestone_id, due_date)은 웹 이관에서 추가합니다.
 * assignee_id는 다른 모듈(user) 소유 리소스라 엔티티 연관 없이 식별자로만 참조합니다(projectId와 같은 기준).
 *
 * <p>{@code status}와 {@code priority}는 {@code Project}와 같은 기준으로 문자열로 둡니다. DB CHECK 제약이 각각 레거시
 * 5종/4종만 허용합니다. {@code role}은 레거시 {@code tasks}에 없는 신규 속성이지만 단일 값이라 부가 테이블 없이 문자열 컬럼으로 두고,
 * priority와 같은 CHECK 제약 방식을 씁니다(MOM-76, 역할은 다중 선택이 아니라 단일 선택입니다). status/priority와 달리 레거시 DB
 * DEFAULT가 없고 null 기본값도 두지 않습니다. mobile의 {@code @NotBlank} 검증이 항상 값을 보장하므로, 불변식이 깨지면 조용히 기본 역할로 채우는
 * 대신 DB {@code NOT NULL} 제약으로 크게 실패합니다.
 */
@Getter
@Entity
@Table(name = "tasks")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class Task extends BaseEntity {

  @Column(name = "workspace_id", nullable = false, columnDefinition = "uuid")
  private UUID workspaceId;

  @Column(name = "project_id", nullable = false, columnDefinition = "uuid")
  private UUID projectId;

  @Column private String label;

  @Column(nullable = false)
  private String title;

  @Column(nullable = false)
  private String status;

  @Column(nullable = false)
  private String priority;

  @Column private String description;

  @Column(name = "assignee_id", columnDefinition = "uuid")
  private UUID assigneeId;

  @Column(name = "milestone_id", columnDefinition = "uuid")
  private UUID milestoneId;

  @Column(name = "due_date")
  private LocalDate dueDate;

  @Column private String role;

  @Column(name = "origin_type", nullable = false)
  private String originType;

  @Column(name = "origin_signal_id", columnDefinition = "uuid")
  private UUID originSignalId;

  /**
   * 완료기준 항목. 자식 엔티티의 전체 생명주기를 이 aggregate가 소유하므로 cascade ALL과 orphanRemoval로 컬렉션 변경이 곧 저장 변경이 되게
   * 합니다. 저장 순서는 {@code position} 컬럼이 담고, 조회는 {@code @OrderBy}로 그 값 오름차순 정렬만 합니다. 순서 부여는 {@code
   * replaceChecklist}가 0부터 연속으로 채웁니다. {@code @OrderColumn}(리스트 인덱스=position)은 저장 position이 0부터 연속이
   * 아니면 빈 인덱스를 null 원소로 채워 조회를 깨뜨리므로 쓰지 않습니다.
   */
  @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
  @JoinColumn(name = "task_id", nullable = false, updatable = false)
  @OrderBy("position ASC")
  private List<TaskChecklistItem> checklistItems = new ArrayList<>();

  /**
   * 민수가 제안하는 열린질문. 완료기준과 달리 이 서버가 쓰지 않으므로 cascade와 orphanRemoval을 두지 않고 조회만 합니다.
   *
   * <p>{@code insertable}과 {@code updatable}을 끄면 이 컬렉션이 inverse가 되어 Hibernate가 {@code task_id}를 쓰지
   * 않습니다. 끄지 않으면 단방향 {@code @OneToMany}는 부모가 FK 주인이 되어 {@code task_id}를 직접 관리합니다.
   *
   * <p>{@code sortOrder}는 생산자가 주는 값이라 비거나 겹칠 수 있어, 겹칠 때는 id로 순서를 고정합니다.
   */
  @OneToMany
  @JoinColumn(name = "task_id", insertable = false, updatable = false)
  @OrderBy("sortOrder ASC, id ASC")
  @Immutable
  private List<TaskOpenQuestion> openQuestions = new ArrayList<>();

  /**
   * 민수가 제안하는 다음행동. 이 서버가 쓰지 않아 읽기 전용으로 매핑합니다. 값은 민수 구현 전까지 같은 backing 계약을 따르는 fixture가
   * 채웁니다(ADR-0011).
   */
  @Column(name = "next_action", insertable = false, updatable = false)
  private String nextAction;

  @Column(name = "deleted_at")
  private Instant deletedAt;

  private Task(CreateTaskCommand command, String label) {
    this.workspaceId = command.workspaceId();
    this.projectId = command.projectId();
    this.label = label;
    this.title = command.title();
    this.description = command.description();
    this.status = command.status().value();
    this.priority = (command.priority() != null ? command.priority() : TaskPriority.MEDIUM).value();
    this.role = command.role() != null ? command.role().value() : null;
    this.milestoneId = command.milestoneId();
    this.assigneeId = command.assigneeId();
    this.dueDate = command.dueDate();
    this.originType = command.origin().value();
    this.originSignalId = command.originSignalId();
  }

  static Task create(CreateTaskCommand command, String label) {
    return new Task(command, label);
  }

  void patch(
      String title,
      boolean titleSet,
      String description,
      boolean descriptionSet,
      TaskStatus status,
      boolean statusSet,
      TaskPriority priority,
      boolean prioritySet,
      UUID milestoneId,
      boolean milestoneSet,
      UUID assigneeId,
      boolean assigneeSet,
      LocalDate dueDate,
      boolean dueDateSet) {
    if (titleSet) {
      this.title = title;
    }
    if (descriptionSet) {
      this.description = description;
    }
    if (statusSet) {
      this.status = status.value();
    }
    if (prioritySet) {
      this.priority = priority.value();
    }
    if (milestoneSet) {
      this.milestoneId = milestoneId;
    }
    if (assigneeSet) {
      this.assigneeId = assigneeId;
    }
    if (dueDateSet) {
      this.dueDate = dueDate;
    }
  }

  void delete() {
    this.deletedAt = Instant.now();
  }

  /**
   * 수정 화면이 저장한 편집 상태 전체로 태스크를 갱신합니다. title, role, priority, status는 mobile이 검증한 값이라 항상 채워집니다.
   * description은 목적을 비운 경우 빈 문자열이나 null로 들어옵니다. assigneeId는 담당자를 지정하면 값이, 비우면 null이 들어옵니다.
   */
  void update(
      String title,
      TaskRole role,
      TaskPriority priority,
      TaskStatus status,
      String description,
      UUID assigneeId) {
    this.title = title;
    this.role = role.value();
    this.priority = priority.value();
    this.status = status.value();
    this.description = description;
    this.assigneeId = assigneeId;
  }

  /** draft 반영이 비교하는 세 필드가 주어진 값과 같은지. 하나라도 다르면 반영하지 않습니다. */
  boolean matchesDraft(TaskDraftValues draft) {
    return Objects.equals(this.title, draft.title())
        && Objects.equals(this.role, draft.role().value())
        && Objects.equals(this.priority, draft.priority().value());
  }

  /**
   * AI가 생성한 draft를 반영합니다. {@link #update}와 달리 세 필드만 바꿉니다. status·description·assignee는 사용자와 다른 경로가
   * 소유하는 값이라 생성 결과가 건드리지 않습니다.
   */
  void applyDraft(TaskDraftValues draft) {
    this.title = draft.title();
    this.role = draft.role().value();
    this.priority = draft.priority().value();
  }

  /**
   * 완료기준 목록을 수정 화면이 저장한 최종 목록으로 교체합니다. id가 없는 항목은 새로 만들고, id가 기존 항목과 맞으면 제목과 완료 상태를 함께 갱신합니다. 최종
   * 목록에 없는 기존 항목은 컬렉션에서 빠지면서 orphanRemoval로 삭제됩니다. 리스트 순서가 그대로 저장 순서라, 각 항목의 position을 목록 인덱스대로 새로
   * 부여합니다.
   *
   * <p>존재하지 않는 id를 거부하는 검증은 이 메서드를 부르는 {@code TaskWriter}가 먼저 합니다. 토글의 없는 항목 처리와 같은 계층에 두어, 엔티티는
   * 검증한 입력을 저장만 합니다.
   *
   * <p>{@code @OrderBy}는 조회 정렬만 하므로 순서(position)는 여기서 목록 인덱스대로 0부터 연속으로 직접 부여합니다.
   */
  void replaceChecklist(List<UpdateTaskCommand.ChecklistItemEdit> edits) {
    Map<UUID, TaskChecklistItem> existingById =
        checklistItems.stream().collect(Collectors.toMap(TaskChecklistItem::getId, item -> item));
    List<TaskChecklistItem> rebuilt = new ArrayList<>();
    for (int position = 0; position < edits.size(); position++) {
      UpdateTaskCommand.ChecklistItemEdit edit = edits.get(position);
      TaskChecklistItem existing = edit.id() == null ? null : existingById.get(edit.id());
      if (existing != null) {
        existing.updateTitle(edit.title());
        existing.changeCompleted(edit.completed());
        existing.changePosition(position);
        rebuilt.add(existing);
      } else {
        rebuilt.add(new TaskChecklistItem(edit.title(), edit.completed(), position));
      }
    }
    checklistItems.clear();
    checklistItems.addAll(rebuilt);
  }
}
