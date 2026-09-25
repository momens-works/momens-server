package works.momens.server.minsu.draft.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;
import static works.momens.server.minsu.draft.ledger.LedgerObservabilityFixture.observability;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import works.momens.server.common.persistence.JpaAuditingConfig;
import works.momens.server.common.test.AbstractPostgresIntegrationTest;
import works.momens.server.minsu.PreparedTaskDraft;
import works.momens.server.minsu.SignalTaskDraftInput;
import works.momens.server.minsu.TaskDraft;
import works.momens.server.minsu.TaskDraftEnrollmentException;
import works.momens.server.minsu.draft.config.MinsuAsyncProperties;
import works.momens.server.minsu.draft.json.MinsuJson;
import works.momens.server.project.task.TaskPriority;
import works.momens.server.project.task.TaskRole;

/** convert 트랜잭션의 원장 적재를 실제 PostgreSQL로 검증한다(MOM-0818, 설계 5.3·5.6·8.6절). */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaAuditingConfig.class)
class TaskDraftGenerationEnrollerIntegrationTest extends AbstractPostgresIntegrationTest {

  private static final Duration DEADLINE = Duration.ofHours(1);
  private static final Duration MARGIN = Duration.ofMinutes(5);

  /** 적재는 실행 정책을 쓰지 않으므로 부등식을 만족하는 아무 값이면 된다. */
  private static final MinsuAsyncProperties.Execution EXECUTION =
      new MinsuAsyncProperties.Execution(
          Duration.ofSeconds(20),
          Duration.ofSeconds(30),
          Duration.ofSeconds(60),
          4,
          List.of(Duration.ofSeconds(10)),
          4);

  private static final TaskDraft BASELINE =
      new TaskDraft("결제 실패율 대응", TaskRole.BACKEND, TaskPriority.HIGH);

  @Autowired private TaskDraftGenerationRepository repository;

  private TaskDraftGenerationEnroller enroller;

  @BeforeEach
  void setUp() {
    enroller =
        new TaskDraftGenerationEnroller(
            repository,
            new MinsuAsyncProperties(true, false, DEADLINE, MARGIN, EXECUTION),
            new MinsuJson(),
            observability());
  }

  @Test
  @DisplayName("convert 시점 snapshot과 baseline을 담은 pending 원장을 적재한다")
  void enrollsPendingLedgerWithSnapshotAndBaseline() {
    UUID taskId = UUID.randomUUID();
    UUID workspaceId = UUID.randomUUID();

    enroller.enroll(enroller.bind(BASELINE, input()), taskId, workspaceId);

    TaskDraftGeneration saved = repository.findByTaskId(taskId).orElseThrow();
    assertAll(
        () -> assertThat(saved.getWorkspaceId()).isEqualTo(workspaceId),
        () -> assertThat(saved.getStatus()).isEqualTo("pending"),
        () -> assertThat(saved.getAttemptCount()).isZero(),
        () -> assertThat(saved.getClaimToken()).isNull(),
        () -> assertThat(saved.getSignalTitle()).isEqualTo("결제 실패율이 올라감"),
        () -> assertThat(saved.getSignalType()).isEqualTo("risk"),
        () -> assertThat(saved.getSignalDescription()).isEqualTo("카드 결제 실패가 늘었다"),
        () -> assertThat(saved.getSignalImpact()).isEqualTo("결제 전환율 하락"),
        () -> assertThat(saved.getSignalEvidence()).contains("결제 화면"),
        // baseline은 convert가 tasks에 쓴 값 그대로다(8.1절). 여기서 어긋나면 반영 CAS가 전부
        // user_edited로 오분류된다.
        () -> assertThat(saved.getBaselineTitle()).isEqualTo("결제 실패율 대응"),
        () -> assertThat(saved.getBaselineRole()).isEqualTo("backend"),
        () -> assertThat(saved.getBaselinePriority()).isEqualTo("high"));
  }

  @Test
  @DisplayName("두 deadline을 적재 시점에 계산해 margin만큼 벌려 저장한다")
  void storesGuardBandBetweenTwoDeadlines() {
    UUID taskId = UUID.randomUUID();
    Instant before = repository.currentDatabaseTime();

    enroller.enroll(enroller.bind(BASELINE, input()), taskId, UUID.randomUUID());

    TaskDraftGeneration saved = repository.findByTaskId(taskId).orElseThrow();
    assertAll(
        () -> assertThat(saved.getReadDeadlineAt()).isAfterOrEqualTo(before.plus(DEADLINE)),
        () ->
            assertThat(Duration.between(saved.getApplyCutoffAt(), saved.getReadDeadlineAt()))
                .isEqualTo(MARGIN),
        // 적재 직후부터 처리 대상이다. 첫 시도를 백오프만큼 미루면 아무 이유 없이 지연된다.
        () -> assertThat(saved.getNextAttemptAt()).isBeforeOrEqualTo(saved.getApplyCutoffAt()));
  }

  @Test
  @DisplayName("적재에 실패하면 전용 예외로 감싸 던진다")
  void wrapsPersistenceFailureInEnrollmentException() {
    UUID taskId = UUID.randomUUID();
    PreparedTaskDraft prepared = enroller.bind(BASELINE, input());
    enroller.enroll(prepared, taskId, UUID.randomUUID());

    // 상류의 signal_actions UNIQUE(signal_id)가 1차 방어라 실제로는 도달하기 어렵지만, 도달했을 때
    // 호출자의 경합 흡수(DataIntegrityViolationException catch)에 삼켜지지 않아야 한다(5.3절).
    assertThatThrownBy(() -> enroller.enroll(prepared, taskId, UUID.randomUUID()))
        .isInstanceOf(TaskDraftEnrollmentException.class)
        .hasMessageContaining(taskId.toString());
  }

  @Test
  @DisplayName("Minsu가 발급하지 않은 준비 결과는 받지 않는다")
  void rejectsForeignPreparedDraft() {
    // 외부 구현을 조용히 무시하면 적재되지 않은 채 ready가 된다.
    assertThatThrownBy(() -> enroller.enroll(() -> BASELINE, UUID.randomUUID(), UUID.randomUUID()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static SignalTaskDraftInput input() {
    return new SignalTaskDraftInput(
        "결제 실패율이 올라감",
        "risk",
        "카드 결제 실패가 늘었다",
        "결제 전환율 하락",
        List.of(new SignalTaskDraftInput.Evidence("결제 화면", "실패율 3%", "전환 하락")));
  }
}
