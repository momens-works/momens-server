package works.momens.server.project.task.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import works.momens.server.common.api.BusinessException;
import works.momens.server.common.api.CommonErrorCode;
import works.momens.server.common.persistence.JpaAuditingConfig;
import works.momens.server.common.test.AbstractPostgresIntegrationTest;
import works.momens.server.outbox.OutboxAppender;
import works.momens.server.project.ProjectSeedSql;
import works.momens.server.project.core.ProjectReader;
import works.momens.server.project.milestone.MilestoneDirectory;
import works.momens.server.project.task.CreateTaskCommand;
import works.momens.server.project.task.TaskDetail;
import works.momens.server.project.task.TaskErrorCode;
import works.momens.server.project.task.TaskReader;
import works.momens.server.project.task.TaskScope;
import works.momens.server.project.task.TaskWriter;
import works.momens.server.project.task.UpdateTaskCommand;
import works.momens.server.project.task.UpdateTaskCommand.ChecklistItemEdit;
import works.momens.server.workspace.label.LabelAllocator;
import works.momens.server.workspace.membership.WorkspaceMembershipReader;
import works.momens.server.workspace.membership.WorkspaceRole;

/**
 * task 수정 public API 검증.
 *
 * <p>실제 PostgreSQL(Testcontainers) 환경에서 본문 수정, 담당자 비우기, 완료기준 전체 교체(완료 상태 배치 갱신, 순서, 빠진 항목 삭제, 없는 id
 * 거부), 즉시 토글, 민수 산출물 보존, 없는 대상 처리까지 확인합니다. workspaces/users/projects는 다른 모듈 소유 테이블이라 FK 대상 행만 네이티브
 * SQL로 만듭니다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, TaskWriterImpl.class, TaskReaderImpl.class})
class TaskWriterIntegrationTest extends AbstractPostgresIntegrationTest {

  @MockitoBean private ProjectReader projectReader;
  @MockitoBean private MilestoneDirectory milestoneDirectory;
  @MockitoBean private WorkspaceMembershipReader workspaceMembershipReader;
  @MockitoBean private LabelAllocator labelAllocator;
  @MockitoBean private OutboxAppender outboxAppender;

  @Autowired private TaskWriter taskWriter;
  @Autowired private TaskReader taskReader;
  @Autowired private TaskRepository taskRepository;
  @Autowired private TestEntityManager entityManager;

  @BeforeEach
  void allowAssigneeReference() {
    when(workspaceMembershipReader.roleOf(any(), any()))
        .thenReturn(Optional.of(WorkspaceRole.MEMBER));
  }

  @Test
  void updateReplacesEditableFields() {
    Fixture fixture = newTask();
    UUID assigneeId = ProjectSeedSql.insertUser(entityManager, "assignee@momens.works");

    TaskDetail updated =
        taskWriter.update(
            command(
                fixture.taskId(),
                "제목 수정",
                "backend",
                assigneeId,
                "high",
                "in_progress",
                "수정한 목적",
                List.of()));

    assertThat(updated.title()).isEqualTo("제목 수정");
    assertThat(updated.role()).isEqualTo("backend");
    assertThat(updated.priority()).isEqualTo("high");
    assertThat(updated.status()).isEqualTo("in_progress");
    assertThat(updated.description()).isEqualTo("수정한 목적");
    assertThat(updated.assigneeId()).isEqualTo(assigneeId);
  }

  @Test
  void updateAllowsUnchangedAssigneeWhoLeftWorkspace() {
    Fixture fixture = newTask();
    UUID assigneeId = ProjectSeedSql.insertUser(entityManager, "former-assignee@momens.works");
    entityManager
        .getEntityManager()
        .createNativeQuery("UPDATE tasks SET assignee_id = ?1 WHERE id = ?2")
        .setParameter(1, assigneeId)
        .setParameter(2, fixture.taskId())
        .executeUpdate();
    entityManager.clear();
    when(workspaceMembershipReader.roleOf(fixture.workspaceId(), assigneeId))
        .thenReturn(Optional.empty());

    TaskDetail updated =
        taskWriter.update(
            command(
                fixture.taskId(),
                "제목 수정",
                "pm",
                assigneeId,
                "medium",
                "todo",
                "수정한 목적",
                List.of()));

    assertThat(updated.title()).isEqualTo("제목 수정");
    assertThat(updated.assigneeId()).isEqualTo(assigneeId);
  }

  @Test
  void updateClearsAssigneeWhenAssigneeIdIsNull() {
    Fixture fixture = newTask();
    UUID assigneeId = ProjectSeedSql.insertUser(entityManager, "clear@momens.works");
    taskWriter.update(
        command(fixture.taskId(), "제목", "pm", assigneeId, "medium", "todo", null, List.of()));

    TaskDetail cleared =
        taskWriter.update(
            command(fixture.taskId(), "제목", "pm", null, "medium", "todo", null, List.of()));

    assertThat(cleared.assigneeId()).isNull();
  }

  @Test
  void updateReplacesChecklistWithCompletedAndOrder() {
    Fixture fixture = newTask();
    TaskDetail seeded =
        taskWriter.update(
            command(
                fixture.taskId(),
                "제목",
                "pm",
                null,
                "medium",
                "todo",
                null,
                List.of(
                    new ChecklistItemEdit(null, "A", false),
                    new ChecklistItemEdit(null, "B", false),
                    new ChecklistItemEdit(null, "C", false))));
    UUID idA = seeded.checklistItems().get(0).id();
    UUID idB = seeded.checklistItems().get(1).id();

    // A는 그대로 두고, B는 제목과 완료 상태를 함께 바꾸고, C는 목록에서 빼고, D를 완료 상태로 새로 추가한다.
    taskWriter.update(
        command(
            fixture.taskId(),
            "제목",
            "pm",
            null,
            "medium",
            "todo",
            null,
            List.of(
                new ChecklistItemEdit(idA, "A", false),
                new ChecklistItemEdit(idB, "B 수정", true),
                new ChecklistItemEdit(null, "D", true))));
    entityManager.flush();
    entityManager.clear();

    TaskDetail reloaded = taskReader.findDetail(fixture.taskId()).orElseThrow();
    assertThat(reloaded.checklistItems())
        .extracting(TaskDetail.ChecklistItem::title)
        .containsExactly("A", "B 수정", "D");
    // 완료 상태는 배치가 보낸 값을 따른다. 기존 항목 B와 새로 추가한 D 모두 완료로 저장된다.
    assertThat(reloaded.checklistItems())
        .extracting(TaskDetail.ChecklistItem::completed)
        .containsExactly(false, true, true);
    assertThat(reloaded.checklistItems().get(0).id()).isEqualTo(idA);
    assertThat(reloaded.checklistItems().get(1).id()).isEqualTo(idB);
  }

  @Test
  void updateRejectsUnknownChecklistItemId() {
    Fixture fixture = newTask();

    assertThatThrownBy(
            () ->
                taskWriter.update(
                    command(
                        fixture.taskId(),
                        "제목",
                        "pm",
                        null,
                        "medium",
                        "todo",
                        null,
                        List.of(new ChecklistItemEdit(UUID.randomUUID(), "없는 항목", false)))))
        .isInstanceOf(BusinessException.class)
        .extracting(exception -> ((BusinessException) exception).getErrorCode())
        .isEqualTo(TaskErrorCode.TASK_CHECKLIST_ITEM_NOT_FOUND);
  }

  @Test
  void updateRejectsDuplicateChecklistItemId() {
    Fixture fixture = newTask();
    TaskDetail seeded =
        taskWriter.update(
            command(
                fixture.taskId(),
                "제목",
                "pm",
                null,
                "medium",
                "todo",
                null,
                List.of(new ChecklistItemEdit(null, "A", false))));
    UUID idA = seeded.checklistItems().get(0).id();

    assertThatThrownBy(
            () ->
                taskWriter.update(
                    command(
                        fixture.taskId(),
                        "제목",
                        "pm",
                        null,
                        "medium",
                        "todo",
                        null,
                        List.of(
                            new ChecklistItemEdit(idA, "A", false),
                            new ChecklistItemEdit(idA, "A 중복", true)))))
        .isInstanceOf(BusinessException.class)
        .extracting(exception -> ((BusinessException) exception).getErrorCode())
        .isEqualTo(CommonErrorCode.COMMON_VALIDATION_FAILED);
  }

  @Test
  void toggleChecklistItemChangesCompleted() {
    Fixture fixture = newTask();
    TaskDetail seeded =
        taskWriter.update(
            command(
                fixture.taskId(),
                "제목",
                "pm",
                null,
                "medium",
                "todo",
                null,
                List.of(new ChecklistItemEdit(null, "완료기준", false))));
    UUID itemId = seeded.checklistItems().get(0).id();

    TaskDetail toggled = taskWriter.toggleChecklistItem(fixture.taskId(), itemId, true);

    assertThat(toggled.checklistItems().get(0).completed()).isTrue();
  }

  @Test
  void toggleChecklistItemRejectsUnknownItem() {
    Fixture fixture = newTask();

    assertThatThrownBy(
            () -> taskWriter.toggleChecklistItem(fixture.taskId(), UUID.randomUUID(), true))
        .isInstanceOf(BusinessException.class)
        .extracting(exception -> ((BusinessException) exception).getErrorCode())
        .isEqualTo(TaskErrorCode.TASK_CHECKLIST_ITEM_NOT_FOUND);
  }

  @Test
  void updateRejectsUnknownTask() {
    assertThatThrownBy(
            () ->
                taskWriter.update(
                    command(
                        UUID.randomUUID(), "제목", "pm", null, "medium", "todo", null, List.of())))
        .isInstanceOf(BusinessException.class)
        .extracting(exception -> ((BusinessException) exception).getErrorCode())
        .isEqualTo(TaskErrorCode.TASK_NOT_FOUND);
  }

  @Test
  void updateKeepsMinsuFields() {
    Fixture fixture = newTask();
    // 민수 산출물은 수정 요청에 없고 이 서버가 쓰지 않는 값이라, 수정 화면이 저장해도 그대로 남아야 한다
    // (docs/spec/mobile-api.md 수정 API가 보존하는 필드).
    UUID questionId = UUID.randomUUID();
    ProjectSeedSql.insertOpenQuestion(
        entityManager, questionId, fixture.taskId(), "권한 거부 시 대체 흐름을 둘지 검토 필요", 0);
    ProjectSeedSql.setNextAction(entityManager, fixture.taskId(), "권한 거부 흐름을 PM과 확정하세요.");

    taskWriter.update(
        command(
            fixture.taskId(),
            "제목 수정",
            "backend",
            null,
            "high",
            "in_progress",
            "수정한 목적",
            List.of(new ChecklistItemEdit(null, "새 완료기준", false))));

    TaskDetail detail = taskReader.findDetail(fixture.taskId()).orElseThrow();
    assertThat(detail.openQuestions())
        .extracting(TaskDetail.OpenQuestion::id)
        .containsExactly(questionId);
    assertThat(detail.nextAction()).isEqualTo("권한 거부 흐름을 PM과 확정하세요.");
  }

  @Test
  void findScopeReturnsScopeAndEmptyForSoftDeleted() {
    Fixture fixture = newTask();

    assertThat(taskReader.findScope(fixture.taskId()))
        .contains(new TaskScope(fixture.workspaceId(), fixture.projectId()));

    entityManager
        .getEntityManager()
        .createNativeQuery("UPDATE tasks SET deleted_at = NOW() WHERE id = ?1")
        .setParameter(1, fixture.taskId())
        .executeUpdate();
    entityManager.clear();

    assertThat(taskReader.findScope(fixture.taskId())).isEmpty();
    assertThat(taskReader.findScope(UUID.randomUUID())).isEmpty();
  }

  private Fixture newTask() {
    UUID ownerId =
        ProjectSeedSql.insertUser(entityManager, "owner-" + UUID.randomUUID() + "@momens.works");
    UUID workspaceId = ProjectSeedSql.insertWorkspace(entityManager, "edit");
    UUID projectId = ProjectSeedSql.insertProject(entityManager, workspaceId, ownerId);
    UUID taskId =
        taskRepository
            .saveAndFlush(
                Task.create(
                    CreateTaskCommand.manual(projectId, workspaceId, "초기 제목", "pm", "medium"),
                    null))
            .getId();
    return new Fixture(workspaceId, projectId, taskId);
  }

  private static UpdateTaskCommand command(
      UUID taskId,
      String title,
      String role,
      UUID assigneeId,
      String priority,
      String status,
      String purpose,
      List<ChecklistItemEdit> checklistItems) {
    return new UpdateTaskCommand(
        taskId, title, role, assigneeId, priority, status, purpose, checklistItems);
  }

  private record Fixture(UUID workspaceId, UUID projectId, UUID taskId) {}
}
