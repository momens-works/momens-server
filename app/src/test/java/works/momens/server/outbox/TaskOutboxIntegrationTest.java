package works.momens.server.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import works.momens.server.common.test.AbstractPostgresIntegrationTest;
import works.momens.server.project.task.PatchTaskCommand;
import works.momens.server.project.task.TaskWriter;
import works.momens.server.user.UserProfile;
import works.momens.server.user.UserService;

@SpringBootTest
class TaskOutboxIntegrationTest extends AbstractPostgresIntegrationTest {

  @Autowired private TaskWriter taskWriter;
  @Autowired private UserService userService;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  @DisplayName("태스크를 연속 수정하면 변경마다 이벤트를 남기고 삭제 이벤트도 저장한다")
  void repeatedUpdatesAndDeletePersistSeparateEvents() {
    UUID taskId = insertTask();

    taskWriter.patch(titlePatch(taskId, "두 번째"));
    taskWriter.patch(titlePatch(taskId, "세 번째"));
    taskWriter.patch(titlePatch(taskId, "세 번째"));
    taskWriter.delete(taskId, null);

    List<String> keys =
        jdbcTemplate.queryForList(
            "SELECT idempotency_key FROM outbox_events WHERE aggregate_id = ? ORDER BY id",
            String.class,
            taskId.toString());
    assertThat(keys).hasSize(3).doesNotHaveDuplicates();
    assertThat(keys.subList(0, 2)).allMatch(key -> key.startsWith("task.updated:" + taskId + ":"));
    assertThat(keys.get(2)).isEqualTo("task.deleted:" + taskId);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT deleted_at IS NOT NULL FROM tasks WHERE id = ?", Boolean.class, taskId))
        .isTrue();
  }

  @Test
  @DisplayName("outbox 저장이 실패하면 태스크 수정도 롤백된다")
  void failedOutboxInsertRollsBackTaskUpdate() {
    UUID taskId = insertTask();
    jdbcTemplate.execute(
        """
        CREATE OR REPLACE FUNCTION fail_task_updated_outbox_insert()
        RETURNS trigger LANGUAGE plpgsql AS $$
        BEGIN
          RAISE EXCEPTION 'task.updated outbox insert 실패(테스트 유도)';
        END;
        $$
        """);
    jdbcTemplate.execute(
        """
        CREATE TRIGGER fail_task_updated_outbox_insert
        BEFORE INSERT ON outbox_events
        FOR EACH ROW
        WHEN (NEW.event_type = 'task.updated')
        EXECUTE FUNCTION fail_task_updated_outbox_insert()
        """);
    try {
      assertThatThrownBy(() -> taskWriter.patch(titlePatch(taskId, "저장되면 안 됨")))
          .hasStackTraceContaining("task.updated outbox insert 실패(테스트 유도)");
    } finally {
      jdbcTemplate.execute("DROP TRIGGER fail_task_updated_outbox_insert ON outbox_events");
      jdbcTemplate.execute("DROP FUNCTION fail_task_updated_outbox_insert()");
    }

    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT title FROM tasks WHERE id = ?", String.class, taskId))
        .isEqualTo("첫 번째");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE aggregate_id = ?",
                Integer.class,
                taskId.toString()))
        .isZero();
  }

  private UUID insertTask() {
    UserProfile user =
        userService.findOrCreate("task-outbox-" + UUID.randomUUID() + "@momens.works", "작성자", null);
    UUID workspaceId = UUID.randomUUID();
    UUID projectId = UUID.randomUUID();
    UUID taskId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO workspaces (id, name, slug) VALUES (?, '모멘스', ?)",
        workspaceId,
        "task-outbox-" + workspaceId);
    jdbcTemplate.update(
        "INSERT INTO projects (id, workspace_id, name, owner_id) VALUES (?, ?, '프로젝트', ?)",
        projectId,
        workspaceId,
        user.id());
    jdbcTemplate.update(
        "INSERT INTO tasks (id, workspace_id, project_id, title, status, priority)"
            + " VALUES (?, ?, ?, '첫 번째', 'todo', 'medium')",
        taskId,
        workspaceId,
        projectId);
    return taskId;
  }

  private PatchTaskCommand titlePatch(UUID taskId, String title) {
    return new PatchTaskCommand(
        taskId, title, true, null, false, null, false, null, false, null, false, null, false, null,
        false, null);
  }
}
