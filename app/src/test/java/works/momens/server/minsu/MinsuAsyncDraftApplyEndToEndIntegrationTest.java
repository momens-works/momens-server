package works.momens.server.minsu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import works.momens.server.auth.AccessTokenTestFactory;
import works.momens.server.common.test.AbstractPostgresIntegrationTest;
import works.momens.server.project.task.TaskPriority;
import works.momens.server.project.task.TaskRole;
import works.momens.server.project.task.TaskStatus;
import works.momens.server.project.task.TaskWriter;
import works.momens.server.project.task.UpdateTaskCommand;
import works.momens.server.user.UserProfile;
import works.momens.server.user.UserService;

/**
 * 비동기 생성 결과가 실제 배선으로 {@code tasks}에 반영되는지 확인한다(MOM-0820, 설계 5.4·8.1·8.3·7.3절).
 *
 * <p>모듈 대역 없이 convert부터 scheduler 반영까지 그대로 태운다. 이 티켓이 새로 만든 두 모듈 의존({@code minsu → project}, {@code
 * minsu → outbox})이 실제 트랜잭션 안에서 함께 커밋되는지가 여기서만 보이기 때문이다. provider만 대역으로 두어 Google 자격 증명 없이 성립하게 한다.
 */
@SpringBootTest(
    properties = {
      "momens.minsu.task-draft.enabled=true",
      "momens.minsu.task-draft.async.enroll=true",
      "momens.minsu.task-draft.async.drain=true",
      "momens.minsu.llm.google.project=test-project",
      "momens.minsu.llm.google.location=global"
    })
@AutoConfigureMockMvc
@Import(MinsuLlmTestFixture.Config.class)
class MinsuAsyncDraftApplyEndToEndIntegrationTest extends AbstractPostgresIntegrationTest {

  private static final String GENERATED_TITLE = "결제 실패율 대응";
  private static final String SIGNAL_TITLE = "결제 실패율이 올라감";

  @Autowired private MockMvc mockMvc;
  @Autowired private AccessTokenTestFactory accessTokens;
  @Autowired private UserService userService;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private TaskWriter taskWriter;
  @Autowired private TaskDraftStatusReader draftStatusReader;
  @Autowired private PlatformTransactionManager transactionManager;

  @Autowired private MinsuLlmTestFixture llm;

  /** 모델이 draft를 정상 생성한 응답. 검증·정규화는 실제 코드가 그대로 수행한다. */
  @BeforeEach
  void stubProvider() {
    llm.respondWith(generated());
  }

  @Test
  @DisplayName("생성 결과를 tasks에 반영하고 worker가 읽을 task.draft_generated를 남긴다")
  void appliesGeneratedDraftAndLeavesEventForWorker() throws Exception {
    UUID taskId = convert("apply-success").taskId();

    awaitCompletion(taskId);

    Map<String, Object> task =
        jdbcTemplate.queryForMap("SELECT title, role, priority FROM tasks WHERE id = ?", taskId);
    assertAll(
        () -> assertThat(task).containsEntry("title", GENERATED_TITLE),
        () -> assertThat(task).containsEntry("role", "backend"),
        () -> assertThat(task).containsEntry("priority", "high"),
        () -> assertThat(completionReason(taskId)).isEqualTo("generated"),
        // 이 단언이 보는 것은 worker가 실제로 마주하는 행의 수다. 호출 횟수는 여기서 증명되지 않는다.
        // `OutboxAppender`가 `idempotency_key`(=`{event_type}:{aggregate_id}`) UNIQUE에
        // `ON CONFLICT DO NOTHING`으로 넣으므로 두 번 불러도 행은 하나다. 단일 호출은 minsu 슬라이스의
        // `RecordingOutboxAppender` 단언이 본다.
        () -> assertThat(draftGeneratedEvents(taskId)).isEqualTo(1));
  }

  @Test
  @DisplayName("사용자가 먼저 편집했으면 편집을 보존하고 event를 발행하지 않는다")
  void userEditBeforeApplyKeepsEditAndSkipsEvent() throws Exception {
    // provider를 붙들어 두고 그 사이에 편집한다. 사용자가 convert 직후 제목을 고치는 것이 이 경합의 실제
    // 모습이고, 뒤늦게 도착한 AI 결과가 그것을 덮으면 명시적으로 한 편집이 사라진다(8.1절).
    CountDownLatch release = latchProvider();
    UUID taskId = convert("apply-user-edit").taskId();
    editTitle(taskId, "사용자가 고친 제목");
    release.countDown();

    awaitCompletion(taskId);

    assertAll(
        () -> assertThat(taskTitle(taskId)).isEqualTo("사용자가 고친 제목"),
        () -> assertThat(completionReason(taskId)).isEqualTo("user_edited"),
        // tasks가 그대로이므로 projection도 바뀔 것이 없다(5.4절).
        () -> assertThat(draftGeneratedEvents(taskId)).isZero());
  }

  @Test
  @DisplayName("한 트랜잭션 안에서 원장을 먼저 읽어도 ready와 fallback title이 함께 나오지 않는다")
  void readOrderNeverYieldsReadyWithFallbackTitle() throws Exception {
    CountDownLatch release = latchProvider();
    UUID taskId = convert("apply-read-order").taskId();

    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            transaction -> {
              // 응답 조립이 하는 순서 그대로다. 원장을 먼저 읽고 그다음 task를 읽는다(7.3절).
              assertThat(draftStatusReader.statusOf(taskId)).isEqualTo(DraftStatus.GENERATING);
              assertThat(taskTitle(taskId)).isEqualTo(SIGNAL_TITLE);

              // 응답 조립 도중에 scheduler가 반영과 종료를 원자적으로 커밋한다.
              release.countDown();
              awaitCompletion(taskId);

              // 같은 트랜잭션이지만 READ COMMITTED라 두 SELECT가 각각 그 시점의 snapshot을 뜬다. 따라서
              // 나중에 읽은 값이 더 최신이고, ready 뒤에 읽는 title은 반영이 끝난 값이다. 격리 수준을
              // REPEATABLE READ 이상으로 올리면 첫 문장이 snapshot을 고정해 이 계약이 조용히 깨진다.
              assertAll(
                  () -> assertThat(draftStatusReader.statusOf(taskId)).isEqualTo(DraftStatus.READY),
                  () -> assertThat(taskTitle(taskId)).isEqualTo(GENERATED_TITLE));
            });
  }

  @Test
  @DisplayName("태스크 상세는 생성 중에 generating을, 반영 뒤에 ready와 최종 title을 함께 돌려준다")
  void taskDetailFlipsToReadyWithGeneratedTitle() throws Exception {
    CountDownLatch release = latchProvider();
    Converted converted = convert("apply-detail-status");

    // 앱이 재조회로 종료를 확인하는 경로다(설계 7.2절). 생성 중에는 generating이고, title은 그동안에도
    // 항상 유효한 fallback이다.
    getTaskDetail(converted)
        .andExpect(jsonPath("$.draft_status").value("generating"))
        .andExpect(jsonPath("$.title").value(SIGNAL_TITLE));

    release.countDown();
    awaitCompletion(converted.taskId());

    // ready와 함께 돌려준 title이 최종 값이다. 앱이 여기서 재조회를 멈춰도 갱신을 놓치지 않는다.
    getTaskDetail(converted)
        .andExpect(jsonPath("$.draft_status").value("ready"))
        .andExpect(jsonPath("$.title").value(GENERATED_TITLE));
  }

  private ResultActions getTaskDetail(Converted converted) throws Exception {
    return mockMvc
        .perform(
            get("/api/mobile/tasks/{taskId}", converted.taskId())
                .header("Authorization", converted.authorization())
                .header("API-Version", "1"))
        .andExpect(status().isOk());
  }

  /** provider를 붙들어 두고 반환 시점을 테스트가 정한다. 해제 전까지 원장은 {@code processing}에 머문다. */
  private CountDownLatch latchProvider() {
    return llm.respondAfterRelease(generated());
  }

  private static TaskDraft generated() {
    return new TaskDraft(GENERATED_TITLE, Role.BACKEND, Priority.HIGH);
  }

  /** convert 결과. 상세 조회에 호출자 토큰이 필요해 함께 돌려준다. */
  private record Converted(UUID taskId, String authorization) {}

  /** convert-to-task를 호출하고 생성된 task의 식별자를 돌려준다. */
  private Converted convert(String slug) throws Exception {
    UserProfile actor = userService.findOrCreate(slug + "@momens.works", "홍길동", null);
    UUID workspace = insertWorkspace(slug);
    addMember(workspace, actor.id(), "owner");
    UUID project = insertProject(workspace, actor.id(), slug);
    UUID signal = insertSignal(workspace, project, SIGNAL_TITLE);

    String authorization = "Bearer " + accessTokens.issueAccessToken(actor.id());
    mockMvc
        .perform(
            post("/api/mobile/signals/{signalId}/actions/convert-to-task", signal)
                .header("Authorization", authorization)
                .header("API-Version", "1"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.task.draft_status").value("generating"));

    UUID taskId =
        jdbcTemplate.queryForObject(
            "SELECT id FROM tasks WHERE origin_signal_id = ?", UUID.class, signal);
    return new Converted(taskId, authorization);
  }

  private void editTitle(UUID taskId, String title) {
    taskWriter.update(
        new UpdateTaskCommand(
            taskId,
            title,
            TaskRole.PM,
            null,
            TaskPriority.MEDIUM,
            TaskStatus.TODO,
            null,
            List.of()));
  }

  private void awaitCompletion(UUID taskId) {
    awaitUntil(() -> completionReason(taskId) != null);
  }

  private static void awaitUntil(BooleanSupplier condition) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) {
        return;
      }
      try {
        TimeUnit.MILLISECONDS.sleep(100);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
    }
    throw new AssertionError("조건이 시간 안에 성립하지 않았습니다");
  }

  private String completionReason(UUID taskId) {
    return jdbcTemplate.queryForObject(
        "SELECT completion_reason FROM minsu_task_draft_generations WHERE task_id = ?",
        String.class,
        taskId);
  }

  private String taskTitle(UUID taskId) {
    return jdbcTemplate.queryForObject(
        "SELECT title FROM tasks WHERE id = ?", String.class, taskId);
  }

  private int draftGeneratedEvents(UUID taskId) {
    return jdbcTemplate.queryForObject(
        "SELECT count(*) FROM outbox_events WHERE event_type = 'task.draft_generated'"
            + " AND aggregate_id = ?",
        Integer.class,
        taskId.toString());
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

  private UUID insertSignal(UUID workspaceId, UUID projectId, String title) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO signals (id, workspace_id, project_id, type, title, description, impact,"
            + " occurred_at) VALUES (?, ?, ?, 'risk', ?, ?, ?, now())",
        id,
        workspaceId,
        projectId,
        title,
        "카드 결제 실패가 늘었다",
        "결제 전환율 하락");
    return id;
  }
}
