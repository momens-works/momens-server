package works.momens.server.project.task.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import works.momens.server.common.persistence.JpaAuditingConfig;
import works.momens.server.common.test.AbstractPostgresIntegrationTest;
import works.momens.server.project.ProjectSeedSql;
import works.momens.server.project.core.ProjectReader;
import works.momens.server.project.task.BoardTask;
import works.momens.server.project.task.CreateTaskCommand;
import works.momens.server.project.task.TaskDetail;
import works.momens.server.project.task.TaskOrigin;
import works.momens.server.project.task.TaskPriority;
import works.momens.server.project.task.TaskReader;
import works.momens.server.project.task.TaskRole;
import works.momens.server.project.task.TaskScope;
import works.momens.server.project.task.TaskStatus;

/**
 * task 조회 public API 검증.
 *
 * <p>실제 PostgreSQL(Testcontainers) 환경에서 보드 조회(상태 필터, 소프트 삭제 제외, 생성 시각 내림차순 정렬)와 상세 조회(저장 필드,
 * checklist 순서, 민수 산출물, 빈 값)를 확인합니다. workspaces/users/projects는 다른 모듈 소유 테이블이라 FK 대상 행만 네이티브 SQL로
 * 만듭니다. 민수 산출물(열린질문, 다음행동)도 이 서버에 쓰기 경로가 없어 네이티브 SQL로 넣습니다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, TaskReaderImpl.class})
class TaskReaderIntegrationTest extends AbstractPostgresIntegrationTest {

  private static final List<String> BOARD_STATUSES = List.of("todo", "in_progress", "done");

  @Autowired private TaskReader taskReader;
  @Autowired private TaskRepository taskRepository;
  @Autowired private TestEntityManager entityManager;
  @MockitoBean private ProjectReader projectReader;

  @Test
  void listTasksByStatusReturnsOnlyGivenStatuses() {
    UUID ownerId = ProjectSeedSql.insertUser(entityManager, "board-owner@momens.works");
    UUID workspaceId = ProjectSeedSql.insertWorkspace(entityManager, "board");
    UUID projectId = ProjectSeedSql.insertProject(entityManager, workspaceId, ownerId);

    saveTask(workspaceId, projectId, "백로그", TaskStatus.BACKLOG, TaskPriority.MEDIUM, TaskRole.PM);
    saveTask(workspaceId, projectId, "투두", TaskStatus.TODO, TaskPriority.MEDIUM, TaskRole.PM);
    saveTask(
        workspaceId, projectId, "진행중", TaskStatus.IN_PROGRESS, TaskPriority.MEDIUM, TaskRole.PM);
    saveTask(workspaceId, projectId, "완료", TaskStatus.DONE, TaskPriority.MEDIUM, TaskRole.PM);
    saveTask(workspaceId, projectId, "취소", TaskStatus.CANCELLED, TaskPriority.MEDIUM, TaskRole.PM);

    List<BoardTask> board = taskReader.listTasksByStatus(projectId, BOARD_STATUSES);

    assertThat(board)
        .extracting(BoardTask::status)
        .containsExactlyInAnyOrder("todo", "in_progress", "done");
    assertThat(board).extracting(BoardTask::title).doesNotContain("백로그", "취소");
  }

  @Test
  void listTasksByStatusExcludesSoftDeletedAndOtherProjects() {
    UUID ownerId = ProjectSeedSql.insertUser(entityManager, "filter-owner@momens.works");
    UUID workspaceId = ProjectSeedSql.insertWorkspace(entityManager, "filter");
    UUID projectId = ProjectSeedSql.insertProject(entityManager, workspaceId, ownerId);
    UUID otherProjectId = ProjectSeedSql.insertProject(entityManager, workspaceId, ownerId);

    saveTask(workspaceId, projectId, "살아있음", TaskStatus.TODO, TaskPriority.MEDIUM, TaskRole.PM);
    UUID deleted =
        saveTask(workspaceId, projectId, "삭제됨", TaskStatus.TODO, TaskPriority.MEDIUM, TaskRole.PM);
    softDelete(deleted);
    saveTask(
        workspaceId, otherProjectId, "다른 프로젝트", TaskStatus.TODO, TaskPriority.MEDIUM, TaskRole.PM);

    List<BoardTask> board = taskReader.listTasksByStatus(projectId, BOARD_STATUSES);

    assertThat(board).extracting(BoardTask::title).containsExactly("살아있음");
  }

  @Test
  void listTasksByStatusReturnsNewestFirstWithRole() {
    UUID ownerId = ProjectSeedSql.insertUser(entityManager, "order-owner@momens.works");
    UUID workspaceId = ProjectSeedSql.insertWorkspace(entityManager, "order");
    UUID projectId = ProjectSeedSql.insertProject(entityManager, workspaceId, ownerId);

    UUID earlier =
        saveTask(
            workspaceId, projectId, "먼저", TaskStatus.TODO, TaskPriority.HIGH, TaskRole.BACKEND);
    UUID later =
        saveTask(
            workspaceId, projectId, "나중", TaskStatus.TODO, TaskPriority.HIGH, TaskRole.FRONTEND);
    // 두 태스크의 created_at이 같으면 id 보조 정렬로 순서가 흔들리므로, 생성 시각을 다르게 고정해 정렬을 결정적으로 만든다.
    setCreatedAt(earlier, "2026-07-06T00:00:00Z");
    setCreatedAt(later, "2026-07-06T00:00:01Z");

    List<BoardTask> board = taskReader.listTasksByStatus(projectId, BOARD_STATUSES);

    assertThat(board).extracting(BoardTask::title).containsExactly("나중", "먼저");
    assertThat(board.get(1).role()).isEqualTo("backend");
  }

  @Test
  void findDetailReturnsStoredFieldsAndChecklistInPositionOrder() {
    UUID ownerId = ProjectSeedSql.insertUser(entityManager, "gyuil@momens.works");
    UUID workspaceId = ProjectSeedSql.insertWorkspace(entityManager, "detail");
    UUID projectId = ProjectSeedSql.insertProject(entityManager, workspaceId, ownerId);
    UUID assigneeId = ProjectSeedSql.insertUser(entityManager, "jinsu@momens.works");

    UUID taskId =
        saveTask(
            workspaceId,
            projectId,
            "1차 와이어프레임",
            TaskStatus.TODO,
            TaskPriority.URGENT,
            TaskRole.BACKEND);
    setDetailColumns(taskId, "이번 범위의 화면 흐름을 정리한다", assigneeId);
    // position 역순으로 넣어 조회 순서가 삽입 순서가 아니라 position 기준임을 확인한다.
    insertChecklistItem(taskId, "두 번째 완료기준", true, 1);
    insertChecklistItem(taskId, "첫 번째 완료기준", false, 0);

    TaskDetail detail = taskReader.findDetail(taskId).orElseThrow();

    assertThat(detail.projectId()).isEqualTo(projectId);
    assertThat(detail.workspaceId()).isEqualTo(workspaceId);
    assertThat(detail.title()).isEqualTo("1차 와이어프레임");
    assertThat(detail.status()).isEqualTo("todo");
    assertThat(detail.priority()).isEqualTo("urgent");
    assertThat(detail.role()).isEqualTo("backend");
    assertThat(detail.assigneeId()).isEqualTo(assigneeId);
    assertThat(detail.description()).isEqualTo("이번 범위의 화면 흐름을 정리한다");
    assertThat(detail.checklistItems())
        .extracting(TaskDetail.ChecklistItem::title)
        .containsExactly("첫 번째 완료기준", "두 번째 완료기준");
    assertThat(detail.checklistItems())
        .extracting(TaskDetail.ChecklistItem::completed)
        .containsExactly(false, true);
  }

  @Test
  void findDetailReadsChecklistWithNonZeroBasedPositions() {
    UUID ownerId = ProjectSeedSql.insertUser(entityManager, "hole-owner@momens.works");
    UUID workspaceId = ProjectSeedSql.insertWorkspace(entityManager, "hole");
    UUID projectId = ProjectSeedSql.insertProject(entityManager, workspaceId, ownerId);

    UUID taskId =
        saveTask(
            workspaceId,
            projectId,
            "비연속 position 완료기준",
            TaskStatus.TODO,
            TaskPriority.MEDIUM,
            TaskRole.BACKEND);
    // 앱 외부에서 1-based·gap으로 들어온 데이터. @OrderColumn(리스트 인덱스=position)이면 빈 인덱스가
    // null 원소가 되어 매핑이 NPE로 죽지만, @OrderBy는 position 순서로 정렬만 하므로 실제 항목만
    // 순서대로 내려주는지 확인한다.
    insertChecklistItem(taskId, "뒤 항목", true, 3);
    insertChecklistItem(taskId, "앞 항목", false, 1);

    TaskDetail detail = taskReader.findDetail(taskId).orElseThrow();

    assertThat(detail.checklistItems())
        .extracting(TaskDetail.ChecklistItem::title)
        .containsExactly("앞 항목", "뒤 항목");
  }

  @Test
  void findDetailReturnsEmptyValuesWhenOptionalFieldsAreMissing() {
    UUID ownerId = ProjectSeedSql.insertUser(entityManager, "empty-owner@momens.works");
    UUID workspaceId = ProjectSeedSql.insertWorkspace(entityManager, "empty");
    UUID projectId = ProjectSeedSql.insertProject(entityManager, workspaceId, ownerId);
    UUID taskId =
        saveTask(workspaceId, projectId, "빈 상세", TaskStatus.TODO, TaskPriority.MEDIUM, TaskRole.PM);

    TaskDetail detail = taskReader.findDetail(taskId).orElseThrow();

    assertThat(detail.assigneeId()).isNull();
    assertThat(detail.description()).isNull();
    assertThat(detail.checklistItems()).isEmpty();
    assertThat(detail.openQuestions()).isEmpty();
    assertThat(detail.nextAction()).isNull();
  }

  @Test
  void findDetailReturnsMinsuFieldsInSortOrder() {
    UUID ownerId = ProjectSeedSql.insertUser(entityManager, "minsu-owner@momens.works");
    UUID workspaceId = ProjectSeedSql.insertWorkspace(entityManager, "minsu");
    UUID projectId = ProjectSeedSql.insertProject(entityManager, workspaceId, ownerId);
    UUID taskId =
        saveTask(
            workspaceId, projectId, "민수 산출물", TaskStatus.TODO, TaskPriority.MEDIUM, TaskRole.PM);

    // 민수(MVP에서는 fixture)가 채우는 값이라 앱 쓰기 경로 없이 SQL로 넣는다. sort_order 역순으로 넣어
    // 조회 순서가 삽입 순서가 아니라 sort_order 기준임을 확인한다.
    insertOpenQuestion(taskId, UUID.randomUUID(), "권한 재요청 주기를 어떻게 정할지 미정", 1);
    insertOpenQuestion(taskId, UUID.randomUUID(), "권한 거부 시 대체 흐름을 둘지 검토 필요", 0);
    setNextAction(taskId, "권한 거부 흐름을 PM과 확정한 뒤 화면 카피를 수정하세요.");

    TaskDetail detail = taskReader.findDetail(taskId).orElseThrow();

    assertThat(detail.openQuestions())
        .extracting(TaskDetail.OpenQuestion::body)
        .containsExactly("권한 거부 시 대체 흐름을 둘지 검토 필요", "권한 재요청 주기를 어떻게 정할지 미정");
    assertThat(detail.nextAction()).isEqualTo("권한 거부 흐름을 PM과 확정한 뒤 화면 카피를 수정하세요.");
  }

  @Test
  void findDetailOrdersOpenQuestionsByIdWhenSortOrderTies() {
    UUID ownerId = ProjectSeedSql.insertUser(entityManager, "tie-owner@momens.works");
    UUID workspaceId = ProjectSeedSql.insertWorkspace(entityManager, "tie");
    UUID projectId = ProjectSeedSql.insertProject(entityManager, workspaceId, ownerId);
    UUID taskId =
        saveTask(
            workspaceId, projectId, "동률 열린질문", TaskStatus.TODO, TaskPriority.MEDIUM, TaskRole.PM);

    // sort_order는 DEFAULT 0이라 생산자가 순서를 안 주면 전부 0이 된다. 그때도 조회 순서가 흔들리지
    // 않도록 id로 보조 정렬하는지 확인한다(signal_evidence와 같은 이유).
    UUID larger = UUID.fromString("ffffffff-ffff-4fff-8fff-ffffffffffff");
    UUID smaller = UUID.fromString("00000000-0000-4000-8000-000000000001");
    insertOpenQuestion(taskId, larger, "나중 질문", 0);
    insertOpenQuestion(taskId, smaller, "먼저 질문", 0);

    TaskDetail detail = taskReader.findDetail(taskId).orElseThrow();

    assertThat(detail.openQuestions())
        .extracting(TaskDetail.OpenQuestion::id)
        .containsExactly(smaller, larger);
  }

  // 글자수는 생산 단계 계약이라 서버가 자르지 않고 CHECK가 막는다(ADR-0011, signal_evidence와 같은 방식). 생산자처럼
  // 네이티브 SQL로 넣으므로 Spring 예외 변환을 타지 않고 Hibernate 예외가 그대로 올라온다. 제약 위반은 트랜잭션을
  // abort시켜 뒤 쿼리가 전부 막히므로 두 필드를 한 테스트에 묶지 않는다.

  @Test
  void openQuestionRejectsBodyOverTheProducedLengthContract() {
    UUID ownerId = ProjectSeedSql.insertUser(entityManager, "question-length@momens.works");
    UUID workspaceId = ProjectSeedSql.insertWorkspace(entityManager, "question-length");
    UUID projectId = ProjectSeedSql.insertProject(entityManager, workspaceId, ownerId);
    UUID taskId =
        saveTask(
            workspaceId, projectId, "열린질문 글자수", TaskStatus.TODO, TaskPriority.MEDIUM, TaskRole.PM);

    assertThatThrownBy(() -> insertOpenQuestion(taskId, UUID.randomUUID(), "질".repeat(51), 0))
        .isInstanceOf(ConstraintViolationException.class);
  }

  @Test
  void nextActionRejectsValueOverTheProducedLengthContract() {
    UUID ownerId = ProjectSeedSql.insertUser(entityManager, "action-length@momens.works");
    UUID workspaceId = ProjectSeedSql.insertWorkspace(entityManager, "action-length");
    UUID projectId = ProjectSeedSql.insertProject(entityManager, workspaceId, ownerId);
    UUID taskId =
        saveTask(
            workspaceId, projectId, "다음행동 글자수", TaskStatus.TODO, TaskPriority.MEDIUM, TaskRole.PM);

    assertThatThrownBy(() -> setNextAction(taskId, "행".repeat(101)))
        .isInstanceOf(ConstraintViolationException.class);
  }

  @Test
  void findDetailReturnsEmptyForSoftDeletedOrUnknownTask() {
    UUID ownerId = ProjectSeedSql.insertUser(entityManager, "deleted-owner@momens.works");
    UUID workspaceId = ProjectSeedSql.insertWorkspace(entityManager, "deleted");
    UUID projectId = ProjectSeedSql.insertProject(entityManager, workspaceId, ownerId);
    UUID deleted =
        saveTask(workspaceId, projectId, "삭제됨", TaskStatus.TODO, TaskPriority.MEDIUM, TaskRole.PM);
    softDelete(deleted);

    assertThat(taskReader.findDetail(deleted)).isEmpty();
    assertThat(taskReader.findDetail(UUID.randomUUID())).isEmpty();
  }

  @Test
  void findScopeReturnsTaskOwnershipAndExcludesSoftDeletedTask() {
    UUID ownerId = ProjectSeedSql.insertUser(entityManager, "scope-owner@momens.works");
    UUID workspaceId = ProjectSeedSql.insertWorkspace(entityManager, "scope");
    UUID projectId = ProjectSeedSql.insertProject(entityManager, workspaceId, ownerId);
    UUID live =
        saveTask(
            workspaceId, projectId, "살아있는 태스크", TaskStatus.TODO, TaskPriority.MEDIUM, TaskRole.PM);
    UUID deleted =
        saveTask(
            workspaceId, projectId, "삭제된 태스크", TaskStatus.TODO, TaskPriority.MEDIUM, TaskRole.PM);
    softDelete(deleted);

    assertThat(taskReader.findScope(live)).contains(new TaskScope(workspaceId, projectId));
    assertThat(taskReader.findScope(deleted)).isEmpty();
    assertThat(taskReader.findScope(UUID.randomUUID())).isEmpty();
  }

  @Test
  void nullRoleTaskIsReadableOnBoardAndDetail() {
    UUID ownerId = ProjectSeedSql.insertUser(entityManager, "web-owner@momens.works");
    UUID workspaceId = ProjectSeedSql.insertWorkspace(entityManager, "web");
    UUID projectId = ProjectSeedSql.insertProject(entityManager, workspaceId, ownerId);

    // 웹에서 만든 태스크는 role 없이 저장된다. NOT NULL 제거로 role=null 저장이 CHECK를 통과하는지,
    // 보드와 상세 조회가 role=null을 문제없이 내려주는지 확인한다.
    UUID taskId =
        saveTask(workspaceId, projectId, "웹 태스크", TaskStatus.TODO, TaskPriority.MEDIUM, null);

    List<BoardTask> board = taskReader.listTasksByStatus(projectId, BOARD_STATUSES);
    assertThat(board).extracting(BoardTask::title).containsExactly("웹 태스크");
    assertThat(board.getFirst().role()).isNull();

    TaskDetail detail = taskReader.findDetail(taskId).orElseThrow();
    assertThat(detail.role()).isNull();
  }

  private UUID saveTask(
      UUID workspaceId,
      UUID projectId,
      String title,
      TaskStatus status,
      TaskPriority priority,
      TaskRole role) {
    return taskRepository
        .saveAndFlush(
            Task.create(
                new CreateTaskCommand(
                    projectId,
                    workspaceId,
                    title,
                    null,
                    status,
                    role,
                    priority,
                    null,
                    null,
                    null,
                    TaskOrigin.MANUAL,
                    null),
                null))
        .getId();
  }

  private void setDetailColumns(UUID taskId, String description, UUID assigneeId) {
    entityManager
        .getEntityManager()
        .createNativeQuery("UPDATE tasks SET description = ?1, assignee_id = ?2 WHERE id = ?3")
        .setParameter(1, description)
        .setParameter(2, assigneeId)
        .setParameter(3, taskId)
        .executeUpdate();
    entityManager.clear();
  }

  private void insertChecklistItem(UUID taskId, String title, boolean completed, int position) {
    entityManager
        .getEntityManager()
        .createNativeQuery(
            "INSERT INTO task_checklist_items (id, task_id, title, completed, position)"
                + " VALUES (?1, ?2, ?3, ?4, ?5)")
        .setParameter(1, UUID.randomUUID())
        .setParameter(2, taskId)
        .setParameter(3, title)
        .setParameter(4, completed)
        .setParameter(5, position)
        .executeUpdate();
    entityManager.clear();
  }

  private void insertOpenQuestion(UUID taskId, UUID id, String body, int sortOrder) {
    ProjectSeedSql.insertOpenQuestion(entityManager, id, taskId, body, sortOrder);
  }

  private void setNextAction(UUID taskId, String nextAction) {
    ProjectSeedSql.setNextAction(entityManager, taskId, nextAction);
  }

  private void softDelete(UUID taskId) {
    entityManager
        .getEntityManager()
        .createNativeQuery("UPDATE tasks SET deleted_at = NOW() WHERE id = ?1")
        .setParameter(1, taskId)
        .executeUpdate();
    entityManager.clear();
  }

  private void setCreatedAt(UUID taskId, String isoInstant) {
    entityManager
        .getEntityManager()
        .createNativeQuery("UPDATE tasks SET created_at = CAST(?1 AS timestamptz) WHERE id = ?2")
        .setParameter(1, isoInstant)
        .setParameter(2, taskId)
        .executeUpdate();
    entityManager.clear();
  }
}
