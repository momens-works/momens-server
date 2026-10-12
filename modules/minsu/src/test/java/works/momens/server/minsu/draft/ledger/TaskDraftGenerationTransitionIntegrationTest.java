package works.momens.server.minsu.draft.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
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

/**
 * 엔티티 전이가 마이그레이션의 CHECK를 만족하는지 실제 PostgreSQL로 검증한다(MOM-0819).
 *
 * <p>MOM-0817의 제약 테스트는 native update로 불변식을 <b>위반</b>시켜 DB가 막는지 봤다. 여기서는 반대 방향으로, 코드가 실제로 수행하는 전이가 그
 * 제약을 <b>통과</b>하는지 본다. 두 방향이 모두 있어야 제약이 과하게 좁은 경우와 넓은 경우를 다 잡는다.
 *
 * <p>전이를 native bulk update가 아니라 엔티티 mutation으로 구현한 이유도 함께 확인한다. JPQL이나 native update는 JPA Auditing
 * 리스너를 우회해 {@code updated_at}이 그대로 남는데, {@code docs/rules/persistence.md}는 수정 테이블의 {@code
 * updated_at}을 Auditing으로 관리하도록 정한다. {@code notification}의 {@code PushDelivery}도 같은 이유로 mutation을
 * 쓴다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaAuditingConfig.class)
class TaskDraftGenerationTransitionIntegrationTest extends AbstractPostgresIntegrationTest {

  private static final int MAX_ATTEMPTS = 4;

  @Autowired private TaskDraftGenerationRepository repository;
  @Autowired private TestEntityManager entityManager;

  @Test
  @DisplayName("claim은 시도 횟수를 올리고 token과 lease를 함께 기록한다")
  void claimIncrementsAttemptAndRecordsOwnership() {
    TaskDraftGeneration generation = persistPending();
    UUID token = UUID.randomUUID();
    Instant leaseExpiresAt = Instant.now().plus(1, ChronoUnit.MINUTES);

    generation.claim(token, leaseExpiresAt);
    entityManager.flush();

    assertAll(
        () -> assertThat(generation.getStatus()).isEqualTo("processing"),
        // 시도 횟수는 claim 시점에 오른다. 결과 기록 시점에 올리면 claim 후 죽은 시도가 세지
        // 않아 상한에 영원히 도달하지 못한다.
        () -> assertThat(generation.getAttemptCount()).isEqualTo(1),
        () -> assertThat(generation.getClaimToken()).isEqualTo(token),
        () -> assertThat(generation.getLeaseExpiresAt()).isNotNull(),
        () -> assertThat(generation.isClaimedBy(token)).isTrue(),
        () -> assertThat(generation.isClaimedBy(UUID.randomUUID())).isFalse());
  }

  @Test
  @DisplayName("retryable 실패는 pending으로 되돌리며 이전 claim을 정리한다")
  void scheduleRetryClearsPreviousClaim() {
    TaskDraftGeneration generation = persistPending();
    generation.claim(UUID.randomUUID(), Instant.now().plus(1, ChronoUnit.MINUTES));
    entityManager.flush();
    Instant nextAttemptAt = Instant.now().plus(10, ChronoUnit.SECONDS);

    generation.scheduleRetry(nextAttemptAt);
    // token이나 lease가 남으면 claim CHECK에 걸려 여기서 터진다.
    entityManager.flush();

    assertAll(
        () -> assertThat(generation.getStatus()).isEqualTo("pending"),
        () -> assertThat(generation.getClaimToken()).isNull(),
        () -> assertThat(generation.getLeaseExpiresAt()).isNull(),
        () -> assertThat(generation.getNextAttemptAt()).isNotNull(),
        // 되돌려도 시도 횟수는 유지된다. 되돌릴 때마다 초기화하면 상한이 의미를 잃는다.
        () -> assertThat(generation.getAttemptCount()).isEqualTo(1));
  }

  @Test
  @DisplayName("종료는 상태와 사유를 함께 기록하고 claim을 정리한다")
  void completeRecordsReasonAndClearsClaim() {
    TaskDraftGeneration generation = persistPending();
    generation.claim(UUID.randomUUID(), Instant.now().plus(1, ChronoUnit.MINUTES));
    entityManager.flush();

    generation.complete(CompletionReason.GENERATED);
    // 상태와 사유가 어긋나면 reason CHECK에 걸려 여기서 터진다.
    entityManager.flush();

    assertAll(
        () -> assertThat(generation.getStatus()).isEqualTo("completed"),
        () -> assertThat(generation.getCompletionReason()).isEqualTo("generated"),
        () -> assertThat(generation.getClaimToken()).isNull(),
        () -> assertThat(generation.getLeaseExpiresAt()).isNull(),
        () -> assertThat(generation.isClaimedBy(UUID.randomUUID())).isFalse());
  }

  @Test
  @DisplayName("모든 종료 사유가 마이그레이션의 CHECK를 통과한다")
  void everyCompletionReasonSatisfiesTheCheck() {
    // enum과 CHECK가 갈라지면 그 사유로 닫는 경로에서만 런타임에 터진다. operationally_closed처럼
    // 운영 중에만 쓰는 값은 그때까지 아무도 모른다.
    for (CompletionReason reason : CompletionReason.values()) {
      TaskDraftGeneration generation = persistPending();

      generation.complete(reason);
      entityManager.flush();

      assertThat(generation.getCompletionReason()).isEqualTo(reason.value());
    }
  }

  @Test
  @DisplayName("시도 상한 도달 여부는 claim으로 올린 횟수로 판정한다")
  void exhaustionIsJudgedByClaimedAttempts() {
    TaskDraftGeneration generation = persistPending();

    for (int attempt = 1; attempt < MAX_ATTEMPTS; attempt++) {
      generation.claim(UUID.randomUUID(), Instant.now().plus(1, ChronoUnit.MINUTES));
      assertThat(generation.isExhausted(MAX_ATTEMPTS)).isFalse();
      generation.scheduleRetry(Instant.now());
    }
    generation.claim(UUID.randomUUID(), Instant.now().plus(1, ChronoUnit.MINUTES));
    entityManager.flush();

    assertThat(generation.isExhausted(MAX_ATTEMPTS)).isTrue();
  }

  @Test
  @DisplayName("반영 창은 apply cutoff를 기준으로 판정한다")
  void applyCutoffBoundsTheApplyWindow() {
    TaskDraftGeneration generation = persistPending();
    Instant cutoff = generation.getApplyCutoffAt();

    assertAll(
        () -> assertThat(generation.isPastApplyCutoff(cutoff.minusMillis(1))).isFalse(),
        // 경계 자체는 지난 것으로 본다. 읽기 투영보다 margin만큼 이른 시각이므로 여기서 멈춰야
        // ready + fallback title 인터리빙이 열리지 않는다.
        () -> assertThat(generation.isPastApplyCutoff(cutoff)).isTrue(),
        () -> assertThat(generation.isPastApplyCutoff(cutoff.plusMillis(1))).isTrue());
  }

  private TaskDraftGeneration persistPending() {
    Instant readDeadline = Instant.now().plus(10, ChronoUnit.MINUTES);
    TaskDraftGeneration saved =
        repository.save(
            TaskDraftGeneration.builder()
                .workspaceId(UUID.randomUUID())
                .taskId(UUID.randomUUID())
                .signalTitle("결제 실패율이 올라감")
                .signalType("risk")
                .signalDescription("카드 결제 실패가 늘었다")
                .signalEvidence("[]")
                .baselineTitle("결제 실패율 대응")
                .baselineRole("backend")
                .baselinePriority("medium")
                .readDeadlineAt(readDeadline)
                .applyCutoffAt(readDeadline.minus(1, ChronoUnit.MINUTES))
                .nextAttemptAt(Instant.now())
                .build());
    entityManager.flush();
    return saved;
  }
}
