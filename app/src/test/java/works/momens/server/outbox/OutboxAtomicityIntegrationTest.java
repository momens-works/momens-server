package works.momens.server.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import works.momens.server.auth.AccessTokenTestFactory;
import works.momens.server.common.test.AbstractPostgresIntegrationTest;
import works.momens.server.user.UserProfile;
import works.momens.server.user.UserService;

/**
 * outbox append 실패가 도메인 write를 함께 rollback시키는지 검증한다(SD-5 원자성 완료 조건). {@link OutboxAppender}는 실제 배선을
 * 유지하고 두 번째 {@code signal.converted_to_task} INSERT만 DB trigger로 실패시켜, 그 전에 실행된 {@code task insert
 * + signal_actions insert + task.created outbox insert}까지 부분 커밋 없이 rollback되는지 확인한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OutboxAtomicityIntegrationTest extends AbstractPostgresIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private AccessTokenTestFactory accessTokens;
  @Autowired private UserService userService;
  @Autowired private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void installOutboxFailureTrigger() {
    jdbcTemplate.execute(
        "DROP TRIGGER IF EXISTS fail_signal_converted_outbox_insert ON outbox_events");
    jdbcTemplate.execute(
        """
        CREATE OR REPLACE FUNCTION fail_signal_converted_outbox_insert()
        RETURNS trigger
        LANGUAGE plpgsql
        AS $$
        BEGIN
          RAISE EXCEPTION 'signal.converted_to_task outbox insert 실패(테스트 유도)';
        END;
        $$
        """);
    jdbcTemplate.execute(
        """
        CREATE TRIGGER fail_signal_converted_outbox_insert
        BEFORE INSERT ON outbox_events
        FOR EACH ROW
        WHEN (NEW.event_type = 'signal.converted_to_task')
        EXECUTE FUNCTION fail_signal_converted_outbox_insert()
        """);
  }

  @AfterEach
  void removeOutboxFailureTrigger() {
    jdbcTemplate.execute(
        "DROP TRIGGER IF EXISTS fail_signal_converted_outbox_insert ON outbox_events");
    jdbcTemplate.execute("DROP FUNCTION IF EXISTS fail_signal_converted_outbox_insert()");
  }

  @Test
  @DisplayName("두 번째 outbox insert가 실패하면 task, signal_action, 첫 outbox도 함께 rollback된다")
  void rollsBackTaskSignalActionAndFirstOutboxWhenSecondOutboxInsertFails() throws Exception {
    UserProfile user = userService.findOrCreate("outbox-atomicity-it@momens.works", "김규일", null);
    UUID workspace = insertWorkspace("outbox-atomicity");
    addMember(workspace, user.id(), "owner");
    UUID project = insertProject(workspace, user.id(), "outbox-atomicity-project");
    UUID signal = insertSignal(workspace, project, "risk", "제목");

    mockMvc
        .perform(
            post("/api/mobile/signals/{signalId}/actions/convert-to-task", signal)
                .header("Authorization", "Bearer " + accessTokens.issueAccessToken(user.id()))
                .header("API-Version", "1"))
        .andExpect(status().is5xxServerError())
        .andExpect(result ->
            assertThat(result.getResolvedException())
                .hasStackTraceContaining("signal.converted_to_task outbox insert 실패(테스트 유도)"));

    Integer taskCount =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM tasks WHERE project_id = ?", Integer.class, project);
    Integer signalActionCount =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM signal_actions WHERE signal_id = ?", Integer.class, signal);
    Integer outboxCount =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM outbox_events WHERE workspace_id = ?", Integer.class, workspace);
    Integer labelSequenceCount =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM workspace_label_sequences "
                + "WHERE workspace_id = ? AND label_prefix = 'MOM'",
            Integer.class,
            workspace);

    assertThat(taskCount).isZero();
    assertThat(signalActionCount).isZero();
    assertThat(outboxCount).isZero();
    assertThat(labelSequenceCount).isZero();
  }

  private UUID insertWorkspace(String slug) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO workspaces (id, name, slug) VALUES (?, ?, ?)", id, "모멘스", slug);
    return id;
  }

  private void addMember(UUID workspaceId, UUID userId, String role) {
    jdbcTemplate.update(
        "INSERT INTO workspace_members (workspace_id, user_id, role) VALUES (?, ?, ?)",
        workspaceId,
        userId,
        role);
  }

  private UUID insertProject(UUID workspaceId, UUID ownerId, String name) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO projects (id, workspace_id, name, owner_id) VALUES (?, ?, ?, ?)",
        id,
        workspaceId,
        name,
        ownerId);
    return id;
  }

  private UUID insertSignal(UUID workspaceId, UUID projectId, String type, String title) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO signals (id, workspace_id, project_id, type, title, description) "
            + "VALUES (?, ?, ?, ?, ?, ?)",
        id,
        workspaceId,
        projectId,
        type,
        title,
        "본문");
    return id;
  }
}
