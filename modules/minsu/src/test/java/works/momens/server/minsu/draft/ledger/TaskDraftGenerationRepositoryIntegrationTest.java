package works.momens.server.minsu.draft.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import works.momens.server.common.persistence.JpaAuditingConfig;
import works.momens.server.common.test.AbstractPostgresIntegrationTest;

/**
 * 생성 원장의 컬럼 매핑과 DB 제약을 실제 PostgreSQL로 검증한다.
 *
 * <p>여기서는 전이가 지켜야 할 불변식을 native update로 직접 위반시켜 DB가 막는지 확인한다. 반대 방향, 즉 실제 전이 코드가 그 제약을 통과하는지는
 * {@link TaskDraftGenerationTransitionIntegrationTest}가 본다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaAuditingConfig.class)
class TaskDraftGenerationRepositoryIntegrationTest extends AbstractPostgresIntegrationTest {

  private static final UUID WORKSPACE_ID = UUID.randomUUID();

  @Autowired private TaskDraftGenerationRepository repository;
  @Autowired private TestEntityManager entityManager;

  @Test
  @DisplayName("적재한 snapshot과 baseline을 그대로 조회한다")
  void savesAndReadsBack() {
    UUID taskId = UUID.randomUUID();
    // timestamptz는 마이크로초까지 저장하므로 왕복 비교를 위해 같은 정밀도로 맞춘다.
    Instant readDeadline =
        Instant.now().plus(10, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MICROS);
    Instant applyCutoff = readDeadline.minus(1, ChronoUnit.MINUTES);
    Instant nextAttempt = readDeadline.minus(10, ChronoUnit.MINUTES);

    repository.save(
        generation(taskId)
            .signalImpact("결제 전환율 하락")
            .signalEvidence("[{\"target\":\"결제\",\"change\":\"실패율 3%\",\"impact\":\"전환 하락\"}]")
            .readDeadlineAt(readDeadline)
            .applyCutoffAt(applyCutoff)
            .nextAttemptAt(nextAttempt)
            .build());
    entityManager.flush();
    entityManager.clear();

    TaskDraftGeneration saved = repository.findByTaskId(taskId).orElseThrow();

    assertThat(saved.getWorkspaceId()).isEqualTo(WORKSPACE_ID);
    assertThat(saved.getSignalTitle()).isEqualTo("결제 실패율이 올라감");
    assertThat(saved.getSignalType()).isEqualTo("risk");
    assertThat(saved.getSignalDescription()).isEqualTo("2026-08-01부터 카드 결제 실패가 늘었다");
    assertThat(saved.getSignalImpact()).isEqualTo("결제 전환율 하락");
    assertThat(saved.getSignalEvidence()).contains("\"target\"").contains("결제");
    assertThat(saved.getBaselineTitle()).isEqualTo("결제 실패율 대응");
    assertThat(saved.getBaselineRole()).isEqualTo("backend");
    assertThat(saved.getBaselinePriority()).isEqualTo("medium");
    // timestamptz ↔ Instant 왕복이 깨지면 deadline 판정이 통째로 어긋난다.
    assertThat(saved.getReadDeadlineAt()).isEqualTo(readDeadline);
    assertThat(saved.getApplyCutoffAt()).isEqualTo(applyCutoff);
    assertThat(saved.getNextAttemptAt()).isEqualTo(nextAttempt);
    assertThat(saved.getStatus()).isEqualTo("pending");
    assertThat(saved.getCompletionReason()).isNull();
    assertThat(saved.getAttemptCount()).isZero();
    assertThat(saved.getClaimToken()).isNull();
    assertThat(saved.getLeaseExpiresAt()).isNull();
    assertThat(saved.getCreatedAt()).isNotNull();
    assertThat(saved.getUpdatedAt()).isNotNull();
  }

  @Test
  @DisplayName("같은 task에 두 번째 원장을 적재하지 못한다")
  void rejectsSecondGenerationForSameTask() {
    UUID taskId = UUID.randomUUID();
    repository.save(generation(taskId).build());
    entityManager.flush();

    assertThatThrownBy(() -> repository.saveAndFlush(generation(taskId).build()))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  /**
   * 원장이 지켜야 할 불변식을 native update로 하나씩 위반시켜 DB가 막는지 확인한다.
   *
   * <p>케이스마다 별도 테스트 인스턴스(=별도 트랜잭션)를 쓴다. 한 트랜잭션 안에서 두 번 위반시키면 첫 실패로 트랜잭션이 abort돼 두 번째 단언이 첫 예외를 다시
   * 보게 되고, 그러면 검증한 것처럼 보이지만 실제로는 아무것도 확인하지 못한다.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("constraintViolations")
  @DisplayName("불변식을 어기는 전이를 DB가 막는다")
  void rejectsConstraintViolation(String label, String assignment, String expected) {
    UUID id = persistPending();

    assertThatThrownBy(() -> update(id, assignment)).rootCause().hasMessageContaining(expected);
  }

  private static Stream<Arguments> constraintViolations() {
    String reason = "minsu_task_draft_generations_reason_check";
    String claim = "minsu_task_draft_generations_claim_check";
    String deadline = "minsu_task_draft_generations_deadline_check";
    return Stream.of(
        Arguments.of("종료 상태인데 사유가 없다", "status = 'completed'", reason),
        Arguments.of("종료 상태가 아닌데 사유가 있다", "completion_reason = 'generated'", reason),
        Arguments.of("processing인데 claim이 없다", "status = 'processing'", claim),
        Arguments.of(
            "pending인데 claim이 있다",
            "claim_token = gen_random_uuid(), lease_expires_at = NOW()",
            claim),
        Arguments.of("token만 남았다", "claim_token = gen_random_uuid()", claim),
        Arguments.of("lease만 남았다", "lease_expires_at = NOW()", claim),
        Arguments.of("허용 목록에 없는 상태", "status = 'stalled'", "status_check"),
        Arguments.of(
            "허용 목록에 없는 종료 사유",
            "status = 'completed', completion_reason = 'gave_up'",
            "completion_reason_check"),
        Arguments.of("tasks 계약 밖의 priority", "baseline_priority = 'critical'", "baseline_priority"),
        Arguments.of("tasks 계약 밖의 role", "baseline_role = 'devops'", "baseline_role"),
        Arguments.of("필수 snapshot 필드가 비었다", "signal_description = NULL", "signal_description"),
        Arguments.of("가드 밴드가 0이다", "apply_cutoff_at = read_deadline_at", deadline),
        Arguments.of(
            "가드 밴드가 뒤집혔다", "apply_cutoff_at = read_deadline_at + INTERVAL '1 second'", deadline));
  }

  @Test
  @DisplayName("정상 전이는 제약을 통과한다")
  void acceptsValidTransitions() {
    UUID id = persistPending();

    // claim: processing + token + lease. 제약이 과하게 좁으면 여기서 막힌다.
    update(id, "status = 'processing', claim_token = gen_random_uuid(), lease_expires_at = NOW()");
    // 종료: 상태와 사유를 함께 기록하고 claim은 정리한다.
    update(
        id,
        "status = 'completed', completion_reason = 'generated',"
            + " claim_token = NULL, lease_expires_at = NULL");

    assertThat(repository.findById(id).orElseThrow().getStatus()).isEqualTo("completed");
  }

  @Test
  @DisplayName("evidence를 jsonb 배열로 저장한다")
  void storesEvidenceAsJsonbArray() {
    UUID taskId = UUID.randomUUID();
    repository.save(
        generation(taskId)
            .signalEvidence("[{\"target\":\"결제\",\"change\":\"실패율 3%\",\"impact\":\"전환 하락\"}]")
            .build());
    entityManager.flush();

    // JSON 문자열이 한 번 더 직렬화되면 jsonb에 배열이 아니라 escape된 string 스칼라가 들어간다.
    // Java 쪽 왕복만 보면 원문이 그대로 돌아와 구분되지 않으므로 DB 표현을 직접 확인한다.
    Object type =
        entityManager
            .getEntityManager()
            .createNativeQuery(
                "SELECT jsonb_typeof(signal_evidence) FROM minsu_task_draft_generations"
                    + " WHERE task_id = :taskId")
            .setParameter("taskId", taskId)
            .getSingleResult();

    assertThat(type).isEqualTo("array");
  }

  private UUID persistPending() {
    TaskDraftGeneration saved = repository.save(generation(UUID.randomUUID()).build());
    entityManager.flush();
    entityManager.clear();
    return saved.getId();
  }

  private void update(UUID id, String assignment) {
    entityManager
        .getEntityManager()
        .createNativeQuery(
            "UPDATE minsu_task_draft_generations SET " + assignment + " WHERE id = :id")
        .setParameter("id", id)
        .executeUpdate();
  }

  private TaskDraftGeneration.TaskDraftGenerationBuilder generation(UUID taskId) {
    Instant readDeadline = Instant.now().plus(10, ChronoUnit.MINUTES);
    return TaskDraftGeneration.builder()
        .workspaceId(WORKSPACE_ID)
        .taskId(taskId)
        .signalTitle("결제 실패율이 올라감")
        .signalType("risk")
        .signalDescription("2026-08-01부터 카드 결제 실패가 늘었다")
        .signalEvidence("[]")
        .baselineTitle("결제 실패율 대응")
        .baselineRole("backend")
        .baselinePriority("medium")
        // 가드 밴드. 두 값을 같게 두면 margin이 0인 행이라 제약이 막는다.
        .readDeadlineAt(readDeadline)
        .applyCutoffAt(readDeadline.minus(1, ChronoUnit.MINUTES))
        .nextAttemptAt(Instant.now());
  }
}
