package works.momens.server.mobile.signal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.IllegalTransactionStateException;
import works.momens.server.auth.AccessTokenTestFactory;
import works.momens.server.common.test.AbstractPostgresIntegrationTest;
import works.momens.server.minsu.PreparedTaskDraft;
import works.momens.server.minsu.SignalTaskDraftGenerator;
import works.momens.server.minsu.SignalTaskDraftInput;
import works.momens.server.user.UserProfile;
import works.momens.server.user.UserService;

/**
 * 비동기 생성이 활성일 때 convert가 원장을 적재하는지 실제 배선으로 확인한다(MOM-0818).
 *
 * <p>Minsu를 mock으로 대체하지 않는다. 이 테스트가 보는 것이 <b>Minsu가 활성 여부를 판정하고 적재까지 수행하는 경로</b> 자체이기 때문이다. 대신
 * provider는 호출되지 않아야 하므로 Google 자격 증명이 없어도 성립한다. 호출되지 않았다는 사실은 {@code async_deferred} 지표로 확인한다.
 */
@SpringBootTest(
    properties = {
      "momens.minsu.task-draft.enabled=true",
      "momens.minsu.task-draft.async.enroll=true",
      "momens.minsu.llm.google.project=test-project",
      "momens.minsu.llm.google.location=global"
    })
@AutoConfigureMockMvc
class MobileSignalConvertAsyncEnrollmentIntegrationTest extends AbstractPostgresIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private AccessTokenTestFactory accessTokens;
  @Autowired private UserService userService;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private MeterRegistry meterRegistry;
  @Autowired private SignalTaskDraftGenerator taskDraftGenerator;

  @Test
  @DisplayName("비동기 활성이면 LLM을 부르지 않고 고정 fallback task와 pending 원장을 함께 커밋한다")
  void enrollsPendingLedgerWithoutCallingProvider() throws Exception {
    UserProfile jinsu = userService.findOrCreate("convert-async-it@momens.works", "신진수", null);
    UUID workspace = insertWorkspace("convert-async");
    addMember(workspace, jinsu.id(), "owner");
    UUID project = insertProject(workspace, jinsu.id(), "convert-async-project");
    UUID signal = insertSignal(workspace, project, "risk", "결제 실패율이 올라감", "카드 결제 실패", "전환율 하락");
    // 같은 컨텍스트를 공유하므로 지표는 증분으로 본다. 절대값으로 보면 실행 순서에 묶인다.
    double deferredBefore = asyncDeferredCount();

    mockMvc
        .perform(
            post("/api/mobile/signals/{signalId}/actions/convert-to-task", signal)
                .header("Authorization", "Bearer " + accessTokens.issueAccessToken(jinsu.id()))
                .header("API-Version", "1"))
        .andExpect(status().isCreated())
        // 요청 경로는 고정 fallback만 돌려준다. 풍부한 draft는 scheduler가 나중에 반영한다.
        .andExpect(jsonPath("$.task.title").value("결제 실패율이 올라감"))
        // 적재했으므로 generating이다. 이 값은 적재가 돌려준 것이고 원장을 다시 읽어 얻은 것이 아니다.
        // 재조회하면 위 fallback title과 짝이 맞지 않는 ready가 나올 수 있다(MOM-0822).
        .andExpect(jsonPath("$.task.draft_status").value("generating"));

    Map<String, Object> ledger =
        jdbcTemplate.queryForMap(
            "SELECT g.status, g.baseline_title, g.baseline_role, g.baseline_priority,"
                + " g.signal_description, g.read_deadline_at, g.apply_cutoff_at, g.attempt_count,"
                + " t.title AS task_title"
                + " FROM minsu_task_draft_generations g JOIN tasks t ON t.id = g.task_id"
                + " WHERE t.project_id = ?",
            project);

    assertThat(ledger)
        .containsEntry("status", "pending")
        .containsEntry("attempt_count", 0)
        .containsEntry("signal_description", "카드 결제 실패")
        // baseline은 같은 트랜잭션에서 tasks에 쓴 값이어야 한다.
        .containsEntry("baseline_title", ledger.get("task_title"))
        .containsEntry("baseline_role", "pm")
        .containsEntry("baseline_priority", "medium");
    assertThat(
            Duration.between(
                instant(ledger, "apply_cutoff_at"), instant(ledger, "read_deadline_at")))
        .isEqualTo(Duration.ofMinutes(5));

    // provider를 부르지 않고 비동기로 미뤘다는 사실을 지표로 확인한다.
    assertThat(asyncDeferredCount() - deferredBefore).isEqualTo(1);
  }

  @Test
  @DisplayName("트랜잭션 밖에서 적재를 부르면 원장을 쓰기 전에 거부한다")
  void rejectsEnrollmentOutsideCallerTransaction() {
    UUID taskId = UUID.randomUUID();
    PreparedTaskDraft prepared =
        taskDraftGenerator.prepare(
            new SignalTaskDraftInput("결제 실패율이 올라감", "risk", "카드 결제 실패", "전환율 하락", List.of()));

    // 트랜잭션이 없으면 saveAndFlush가 자기 트랜잭션을 열어 원장만 커밋한다. task 없이 남은 그 행은
    // 적재에 성공했으므로 실패율 지표에도 잡히지 않는다(MANDATORY로 막는 대상).
    assertThatThrownBy(() -> taskDraftGenerator.enroll(prepared, taskId, UUID.randomUUID()))
        .isInstanceOf(IllegalTransactionStateException.class);

    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM minsu_task_draft_generations WHERE task_id = ?",
                Long.class,
                taskId))
        .isZero();
  }

  private double asyncDeferredCount() {
    Counter counter =
        meterRegistry
            .find("momens.minsu.task.draft.requests")
            .tag("fallback.reason", "async_deferred")
            .counter();
    return counter == null ? 0 : counter.count();
  }

  private static Instant instant(Map<String, Object> row, String column) {
    return ((Timestamp) row.get(column)).toInstant();
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

  private UUID insertSignal(
      UUID workspaceId,
      UUID projectId,
      String type,
      String title,
      String description,
      String impact) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO signals (id, workspace_id, project_id, type, title, description, impact,"
            + " occurred_at) VALUES (?, ?, ?, ?, ?, ?, ?, now())",
        id,
        workspaceId,
        projectId,
        type,
        title,
        description,
        impact);
    return id;
  }
}
