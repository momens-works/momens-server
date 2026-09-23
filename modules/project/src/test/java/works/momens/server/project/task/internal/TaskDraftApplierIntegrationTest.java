package works.momens.server.project.task.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import works.momens.server.common.persistence.JpaAuditingConfig;
import works.momens.server.common.test.AbstractPostgresIntegrationTest;
import works.momens.server.project.ProjectSeedSql;
import works.momens.server.project.task.ApplyTaskDraftCommand;
import works.momens.server.project.task.CreateTaskCommand;
import works.momens.server.project.task.TaskDraftApplier;
import works.momens.server.project.task.TaskDraftApplyResult;
import works.momens.server.project.task.TaskDraftValues;
import works.momens.server.project.task.TaskPriority;
import works.momens.server.project.task.TaskRole;
import works.momens.server.project.task.TaskStatus;

/**
 * 조건부 draft 반영 public API 검증(MOM-0820, 설계 8.1절).
 *
 * <p>실제 PostgreSQL에서 baseline 일치 시 반영, 세 필드 중 하나라도 다를 때의 전부-아니면-전무, 소프트 삭제된 task 처리, 반영이 건드리지 않는
 * 필드를 확인합니다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, TaskDraftApplierImpl.class})
class TaskDraftApplierIntegrationTest extends AbstractPostgresIntegrationTest {

  private static final TaskDraftValues BASELINE =
      new TaskDraftValues("초기 제목", TaskRole.PM, TaskPriority.MEDIUM);
  private static final TaskDraftValues DRAFT =
      new TaskDraftValues("결제 실패율 대응", TaskRole.BACKEND, TaskPriority.HIGH);

  @Autowired private TaskDraftApplier taskDraftApplier;
  @Autowired private TaskRepository taskRepository;
  @Autowired private TestEntityManager entityManager;

  @Test
  @DisplayName("baseline과 일치하면 세 필드를 반영한다")
  void appliesDraftWhenBaselineMatches() {
    UUID taskId = newTask();

    TaskDraftApplyResult result =
        taskDraftApplier.apply(new ApplyTaskDraftCommand(taskId, BASELINE, DRAFT));

    // 여기서만 flush·clear로 DB를 거쳐 다시 읽는다. 1차 캐시가 돌려주는 메모리 상태만 보면 매핑과
    // tasks_role_check(backend 허용)를 한 번도 지나지 않는다.
    entityManager.flush();
    entityManager.clear();
    Task task = reload(taskId);
    assertAll(
        () -> assertThat(result).isEqualTo(TaskDraftApplyResult.APPLIED),
        () -> assertThat(task.getTitle()).isEqualTo("결제 실패율 대응"),
        () -> assertThat(task.getRole()).isEqualTo("backend"),
        () -> assertThat(task.getPriority()).isEqualTo("high"));
  }

  @Test
  @DisplayName("반영은 세 필드만 바꾸고 사용자·다른 경로가 소유하는 값은 그대로 둔다")
  void applyLeavesFieldsOutsideTheDraftUntouched() {
    UUID taskId = newTask();

    taskDraftApplier.apply(new ApplyTaskDraftCommand(taskId, BASELINE, DRAFT));

    Task task = reload(taskId);
    assertAll(
        () -> assertThat(task.getStatus()).isEqualTo("todo"),
        () -> assertThat(task.getDescription()).isEqualTo("초기 목적"),
        () -> assertThat(task.getAssigneeId()).isNull());
  }

  @Test
  @DisplayName("세 필드 중 하나라도 baseline과 다르면 draft 전체를 반영하지 않는다")
  void mismatchOnAnyFieldDiscardsTheWholeDraft() {
    // priority 하나만 달라진 경우다. 필드별로 나눠 일치하는 것만 반영하면 사용자가 판단한 priority를
    // 전제로 쓰이지 않은 AI title이 함께 남아 draft가 사람과 모델의 혼합물이 된다(8.1절).
    UUID taskId = newTask();
    reload(taskId).update("초기 제목", TaskRole.PM, TaskPriority.HIGH, TaskStatus.TODO, "초기 목적", null);
    entityManager.flush();

    TaskDraftApplyResult result =
        taskDraftApplier.apply(new ApplyTaskDraftCommand(taskId, BASELINE, DRAFT));

    Task task = reload(taskId);
    assertAll(
        () -> assertThat(result).isEqualTo(TaskDraftApplyResult.BASELINE_MISMATCH),
        () -> assertThat(task.getTitle()).isEqualTo("초기 제목"),
        () -> assertThat(task.getRole()).isEqualTo("pm"),
        () -> assertThat(task.getPriority()).isEqualTo("high"));
  }

  @Test
  @DisplayName("소프트 삭제된 task에는 반영하지 않는다")
  void doesNotApplyToSoftDeletedTask() {
    // prod는 레거시 momens-api와 DB를 공유하므로 삭제가 레거시 쪽에서 일어날 수 있다. 세 필드만 비교하면
    // 삭제된 행에 결과를 쓰고 성공으로 집계된다(8.1절).
    UUID taskId = newTask();
    softDelete(taskId);

    TaskDraftApplyResult result =
        taskDraftApplier.apply(new ApplyTaskDraftCommand(taskId, BASELINE, DRAFT));

    assertAll(
        () -> assertThat(result).isEqualTo(TaskDraftApplyResult.TASK_GONE),
        () -> assertThat(reload(taskId).getTitle()).isEqualTo("초기 제목"));
  }

  @Test
  @DisplayName("없는 task도 task_gone으로 돌려준다")
  void missingTaskIsAlsoGone() {
    assertThat(
            taskDraftApplier.apply(new ApplyTaskDraftCommand(UUID.randomUUID(), BASELINE, DRAFT)))
        .isEqualTo(TaskDraftApplyResult.TASK_GONE);
  }

  private Task reload(UUID taskId) {
    return taskRepository.findById(taskId).orElseThrow();
  }

  private void softDelete(UUID taskId) {
    entityManager
        .getEntityManager()
        .createNativeQuery("UPDATE tasks SET deleted_at = ?1 WHERE id = ?2")
        .setParameter(1, Instant.now())
        .setParameter(2, taskId)
        .executeUpdate();
    entityManager.clear();
  }

  private UUID newTask() {
    UUID ownerId =
        ProjectSeedSql.insertUser(entityManager, "owner-" + UUID.randomUUID() + "@momens.works");
    UUID workspaceId = ProjectSeedSql.insertWorkspace(entityManager, "apply-draft");
    UUID projectId = ProjectSeedSql.insertProject(entityManager, workspaceId, ownerId);
    Task task =
        taskRepository.saveAndFlush(
            Task.create(
                CreateTaskCommand.manual(
                    projectId,
                    workspaceId,
                    "초기 제목",
                    TaskStatus.TODO,
                    TaskRole.PM,
                    TaskPriority.MEDIUM),
                null));
    // description은 builder에 없어 수정 경로로 채운다. 반영이 건드리지 않는 필드를 확인하려면 값이 있어야 한다.
    task.update("초기 제목", TaskRole.PM, TaskPriority.MEDIUM, TaskStatus.TODO, "초기 목적", null);
    entityManager.flush();
    return task.getId();
  }
}
