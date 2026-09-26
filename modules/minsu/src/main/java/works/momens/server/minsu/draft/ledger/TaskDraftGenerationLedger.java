package works.momens.server.minsu.draft.ledger;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import works.momens.server.minsu.SignalTaskDraftInput;
import works.momens.server.minsu.TaskDraft;
import works.momens.server.minsu.draft.config.MinsuAsyncProperties;
import works.momens.server.minsu.draft.config.MinsuAsyncProperties.Execution;
import works.momens.server.minsu.draft.generation.AsyncGenerationResult;
import works.momens.server.minsu.draft.json.MinsuJson;
import works.momens.server.outbox.OutboxAppender;
import works.momens.server.project.task.ApplyTaskDraftCommand;
import works.momens.server.project.task.TaskDraftApplier;
import works.momens.server.project.task.TaskDraftApplyResult;
import works.momens.server.project.task.TaskDraftValues;
import works.momens.server.project.task.TaskPriority;
import works.momens.server.project.task.TaskRole;

/**
 * claim과 결과 기록 트랜잭션(docs/design/minsu-async-task-draft-design.md 7.1·8.2·8.5절).
 *
 * <p>claim 트랜잭션이 시도 횟수와 소유권을 먼저 커밋하고, provider 호출은 트랜잭션 <b>밖</b>에서 이뤄진다. 결과 기록은 다시 행을 잠그고 claim
 * token을 재검증한 뒤에만 반영한다. 서버가 기록 전에 종료되면 lease 만료 후 회수되며 이는 at-least-once 특성 안에 있다.
 *
 * <p>성공 결과의 {@code tasks} 반영과 원장 terminal 전이, {@code task.draft_generated} 발행은 {@link #record}의 한
 * 트랜잭션 안에 있다(8.3절). 이 세 가지는 나눌 수 없다.
 */
@Slf4j
@Component
class TaskDraftGenerationLedger {

  private static final String EVENT_TASK_DRAFT_GENERATED = "task.draft_generated";

  private final TaskDraftGenerationRepository repository;
  private final MinsuAsyncProperties asyncProperties;
  private final MinsuJson json;
  private final TaskDraftApplier taskDraftApplier;
  private final OutboxAppender outboxAppender;
  private final MinsuLedgerObservability observability;

  TaskDraftGenerationLedger(
      TaskDraftGenerationRepository repository,
      MinsuAsyncProperties asyncProperties,
      MinsuJson json,
      TaskDraftApplier taskDraftApplier,
      OutboxAppender outboxAppender,
      MinsuLedgerObservability observability) {
    this.repository = repository;
    this.asyncProperties = asyncProperties;
    this.json = json;
    this.taskDraftApplier = taskDraftApplier;
    this.outboxAppender = outboxAppender;
    this.observability = observability;
  }

  /**
   * 처리 대상 원장을 claim한다. lease가 만료된 {@code processing}을 <b>먼저</b> 회수하고, 남은 자리를 재시도 시각이 지난 {@code
   * pending}으로 채운다.
   *
   * <p>두 경로를 한 쿼리로 합치지 않은 이유는 각각 다른 부분 인덱스를 타기 때문이다. {@code OR}로 묶으면 둘 다 놓친다.
   *
   * <p><b>회수를 먼저 보는 이유는 기아를 막기 위해서다.</b> 신규를 먼저 보면 매 주기 {@code pending}이 슬롯을 모두 채우는 동안 만료 행이 한 번도
   * 집히지 않고, 그대로 {@code apply_cutoff_at}을 지나면 두 쿼리 모두에서 빠져 영영 회수되지 않는다. "lease 만료 후 다음 주기가 회수한다"는
   * 8.5절의 복구 보장이 부하에 따라 깨지는 것이다.
   *
   * <p>반대 방향의 기아는 생기지 않는다. 만료 집합은 유한하고 자기 자신을 줄인다. 회수된 행은 {@code processing}이 되어 그 집합에서 빠지고, 회수마다
   * 시도 횟수가 올라 결국 상한에서 종료된다. 새로 들어오는 것은 장애로 lease가 만료된 행뿐이다. 반면 {@code pending}은 사용자 요청으로 계속 유입되는 무한
   * 스트림이라, 우선권을 주면 밀리는 쪽이 영원히 밀린다.
   */
  @Transactional
  public List<ClaimedGeneration> claimDue(int limit) {
    Execution execution = asyncProperties.execution();
    List<TaskDraftGeneration> reclaimed = repository.lockExpiredProcessing(limit);
    observability.recordReclaims(reclaimed.size());
    List<TaskDraftGeneration> duePending =
        reclaimed.size() < limit
            ? repository.lockDuePending(limit - reclaimed.size())
            : List.<TaskDraftGeneration>of();
    if (reclaimed.isEmpty() && duePending.isEmpty()) {
      return List.of();
    }
    Instant now = repository.currentDatabaseTime();
    Instant leaseExpiresAt = now.plus(execution.lease());
    List<ClaimedGeneration> claimed = new ArrayList<>();
    // 회수 행은 대기 지연을 재지 않는다. 그쪽 next_attempt_at은 직전 시도의 예약 시각이라 구간에 직전
    // 실행 시간과 lease 만료 대기가 통째로 들어간다. 그러면 이 지표가 scheduler 적체가 아니라 멈춘
    // 호출의 길이를 재게 된다. 회수가 얼마나 밀리는지는 expired.lease.max.age gauge가 이미 본다.
    claimInto(claimed, reclaimed, execution, now, leaseExpiresAt, ClaimSource.RECLAIMED);
    claimInto(claimed, duePending, execution, now, leaseExpiresAt, ClaimSource.DUE_PENDING);
    return claimed;
  }

  private void claimInto(
      List<ClaimedGeneration> claimed,
      List<TaskDraftGeneration> due,
      Execution execution,
      Instant now,
      Instant leaseExpiresAt,
      ClaimSource source) {
    for (TaskDraftGeneration generation : due) {
      if (generation.isExhausted(execution.maxAttempts())) {
        // 마지막 시도를 claim한 뒤 결과를 기록하기 전에 종료된 잔여 행이다. 다시 집으면 상한을 넘겨
        // 실행하게 되므로 여기서 종결한다.
        complete(generation, CompletionReason.RETRY_EXHAUSTED);
        // claim만 남기고 죽은 잔여 행이라 어떤 실패였는지 알 수 없다.
        observability.recordRetryExhaustion(MinsuLedgerObservability.NO_OUTCOME);
        continue;
      }
      if (source == ClaimSource.DUE_PENDING) {
        // 재시도 시각이 지난 뒤 실제로 집히기까지의 지연. 배치가 밀리는 정도가 여기로 드러난다.
        observability.recordClaimWait(Duration.between(generation.getNextAttemptAt(), now));
      }
      UUID claimToken = UUID.randomUUID();
      generation.claim(claimToken, leaseExpiresAt);
      claimed.add(
          new ClaimedGeneration(
              generation.getId(), generation.getTaskId(), claimToken, snapshotOf(generation)));
    }
  }

  /**
   * 반영 창이 닫힌 미종료 원장을 종료로 닫는다(11.1절).
   *
   * <p>사유는 {@code operationally_closed}가 아니라 <b>{@code deadline_exceeded}</b>다. 전자는 운영자가 명시적으로 끝내는
   * 경우이고(11.1절), 이건 나이 상한을 넘겨 자동으로 닫히는 것이라 {@link #record}가 같은 상황에 이미 쓰는 사유와 같다.
   *
   * <p>bulk update가 아니라 행을 잠그고 엔티티를 바꾼다. bulk는 JPA Auditing을 우회해 {@code updated_at}이 멈추고(MOM-0819
   * 코멘트 1번), 무엇보다 종료 지표가 이 경로만 비게 된다. 한 번에 처리할 수를 제한해 rollback 직후처럼 대상이 많을 때도 트랜잭션이 길어지지 않게 한다.
   *
   * @return 이번 주기에 닫은 수. 남은 것이 있으면 다음 주기가 이어서 처리한다
   */
  @Transactional
  public int closeAbandoned(int limit) {
    List<TaskDraftGeneration> abandoned = repository.lockAbandoned(limit);
    for (TaskDraftGeneration generation : abandoned) {
      complete(generation, CompletionReason.DEADLINE_EXCEEDED);
    }
    return abandoned.size();
  }

  /**
   * 실행 결과를 원장에 기록한다(9.2절 표).
   *
   * <p>claim token이 다르면 그 결과는 stale이므로 버린다(8.2절). lease가 만료돼 다른 worker가 재claim한 뒤 이전 실행이 뒤늦게 돌아오는
   * 경우이고, at-least-once와 만료 lease를 택한 이상 정상 동작이다.
   */
  @Transactional
  public void record(ClaimedGeneration claim, AsyncGenerationResult result) {
    TaskDraftGeneration generation = repository.lockById(claim.id()).orElseThrow();
    if (!generation.isClaimedBy(claim.claimToken())) {
      observability.recordStaleResult();
      log.warn("만료된 minsu claim 결과 무시 taskId={} outcome={}", claim.taskId(), result.outcome());
      return;
    }
    Instant now = repository.currentDatabaseTime();
    if (generation.isPastApplyCutoff(now)) {
      // 반영 창이 닫힌 뒤의 결과는 쓰지 않는다(8.6절). 읽기 투영이 이미 ready로 알렸을 수 있어, 여기서
      // 반영하면 ready로 알린 title이 뒤늦게 바뀐다.
      complete(generation, CompletionReason.DEADLINE_EXCEEDED);
      return;
    }
    switch (result.outcome()) {
      case GENERATED, GENERATED_TITLE_FALLBACK, GENERATED_TRUNCATED ->
          applyDraft(generation, result.draft(), now);
      // 입력이 그대로이므로 재시도해도 같다.
      case INSUFFICIENT_CONTEXT -> complete(generation, CompletionReason.INSUFFICIENT_CONTEXT);
      // 설정 수정은 배포 사건이라 재시도로 기다리지 않는다. claim 자체가 설정 유효를 전제하므로
      // (11.2절) 이 값은 claim 이후 설정이 무효해진 경합에서만 도달한다.
      case INVALID_CONFIG -> complete(generation, CompletionReason.INVALID_CONFIG);
      // structured output 위반은 결정적 오류가 아니다. 같은 입력으로 반복 실패하면 프롬프트·스키마
      // 문제이고 그것은 retry_exhausted 중 failure.reason별 건수와 구성비로 구분한다.
      case TIMEOUT, PROVIDER_ERROR, INVALID_RESPONSE, INVALID_OUTPUT ->
          scheduleRetryOrExhaust(generation, now, result.outcome().reason());
      // 이 둘은 요청 경로의 값이라 claim된 실행에서는 나올 수 없다. 나왔다면 실행 경로가 요청 경로의
      // 분기를 잘못 타고 있는 것이므로 조용히 닫지 않는다.
      case DISABLED, DEFERRED ->
          throw new IllegalStateException("비동기 실행이 요청 경로 outcome을 돌려줬습니다: " + result.outcome());
      // outcome이 늘어나면 여기서 크게 실패해야 한다. arrow switch 문은 enum을 다 덮지 않아도 컴파일되고,
      // 그대로 두면 어떤 전이도 없이 트랜잭션이 정상 커밋된다. 원장은 processing으로 남아 lease 만료마다
      // 재claim되므로 provider 비용만 반복되고 아무도 그 사실을 모른다.
      default -> throw new IllegalStateException("처리하지 않은 생성 outcome입니다: " + result.outcome());
    }
  }

  /**
   * 생성 결과를 {@code tasks}에 조건부로 반영하고 원장을 닫는다(8.1·8.3절).
   *
   * <p>반영·원장 종료·event append가 모두 {@link #record}의 트랜잭션 안에서 일어난다. 나누면 반영은 커밋됐는데 원장이 {@code
   * processing}으로 남는 구간이 생기고, 그 뒤 재시도가 baseline 불일치를 보고 사용자 편집으로 오분류한다. 어느 단계에서 실패하든 예외가 그대로 올라가
   * 전체가 롤백된다.
   *
   * <p>{@code task.draft_generated}는 <b>실제로 갱신했을 때만</b> 발행한다(5.4절). {@code tasks}가 그대로인 종료에서는
   * projection도 바뀔 것이 없다.
   *
   * <p>생성 결과가 baseline과 같은 값일 수 있다. {@code GENERATED_TITLE_FALLBACK}은 성공 outcome이면서 title이 convert
   * 시점 고정 fallback과 같은 규칙으로 만들어지므로, role·priority까지 {@code pm}·{@code medium}이면 세 값이 정확히 일치한다. 이때
   * {@code APPLIED}가 돌아오지만 dirty check가 UPDATE를 내지 않아 {@code tasks}는 그대로다. 원장은 성공으로 닫되 발행은 건너뛴다.
   * 비교를 {@code project}가 아니라 여기서 하는 이유는 baseline을 소유한 쪽이 이 모듈이고, "값이 그대로다"를 결과 enum으로 만들면 {@code
   * project}가 알 필요 없는 어휘가 늘기 때문이다(8.1절이 {@code USER_EDITED}를 두지 않은 것과 같은 이유).
   */
  private void applyDraft(TaskDraftGeneration generation, TaskDraft draft, Instant now) {
    TaskDraftValues baseline =
        new TaskDraftValues(
            generation.getBaselineTitle(),
            TaskRole.from(generation.getBaselineRole()).orElseThrow(IllegalStateException::new),
            TaskPriority.from(generation.getBaselinePriority())
                .orElseThrow(IllegalStateException::new));
    TaskDraftValues generated = new TaskDraftValues(draft.title(), draft.role(), draft.priority());
    TaskDraftApplyResult applied =
        taskDraftApplier.apply(
            new ApplyTaskDraftCommand(generation.getTaskId(), baseline, generated));
    switch (applied) {
      case APPLIED -> {
        complete(generation, CompletionReason.GENERATED);
        // 성공 반영에서만 end-to-end 지연을 남긴다. 다른 종료는 tasks를 바꾸지 않아 잴 구간이 없다.
        // now는 호출자가 이미 DB에서 읽어 둔 값이다. NOW()는 transaction_timestamp라 같은 트랜잭션에서
        // 다시 물어도 값이 같고 왕복만 하나 더 나간다.
        observability.recordGenerationDuration(Duration.between(generation.getCreatedAt(), now));
        if (!generated.equals(baseline)) {
          outboxAppender.append(
              generation.getWorkspaceId(),
              "task",
              generation.getTaskId().toString(),
              EVENT_TASK_DRAFT_GENERATED,
              Map.of());
        }
      }
      // baseline과 달라진 이유를 project는 모른다. baseline을 소유한 이쪽이 사용자 편집으로 해석한다(8.1절).
      case BASELINE_MISMATCH -> complete(generation, CompletionReason.USER_EDITED);
      case TASK_GONE -> complete(generation, CompletionReason.TASK_GONE);
      // 반영 결과가 늘어나면 크게 실패한다. 빠뜨리면 원장이 닫히지 않은 채 커밋된다(위 switch와 같은 이유).
      default -> throw new IllegalStateException("처리하지 않은 draft 반영 결과입니다: " + applied);
    }
  }

  private void scheduleRetryOrExhaust(
      TaskDraftGeneration generation, Instant now, String failureReason) {
    Execution execution = asyncProperties.execution();
    if (generation.isExhausted(execution.maxAttempts())) {
      complete(generation, CompletionReason.RETRY_EXHAUSTED);
      // 어떤 실패로 소진됐는지는 종료 사유만으로 갈리지 않는다. timeout 편중과 invalid_output 편중은
      // 대응이 정반대다(9.2절).
      observability.recordRetryExhaustion(failureReason);
      return;
    }
    // 백오프는 결과를 기록한 시점부터 잰다. claim 시점부터 재면 실행에 걸린 시간만큼 대기가 짧아진다.
    generation.scheduleRetry(now.plus(execution.backoffFor(generation.getAttemptCount())));
  }

  /** 집힌 행이 어디서 왔는지. 대기 지연을 재는 대상이 갈리므로 호출부에서 의미가 보여야 한다. */
  private enum ClaimSource {
    /** lease가 만료돼 회수한 행. next_attempt_at이 직전 시도의 예약 시각이라 대기 지연을 재지 않는다. */
    RECLAIMED,
    /** 재시도 시각이 지난 신규·재시도 행. 이쪽의 대기가 scheduler 적체다. */
    DUE_PENDING
  }

  /**
   * 종료 전이를 지표와 묶는다.
   *
   * <p>{@code complete}를 직접 부르는 자리가 여럿이라 하나라도 빠지면 그 사유만 조용히 집계에서 사라진다. 전이와 계측을 한 자리로 모아 그 경로를 없앤다.
   */
  private void complete(TaskDraftGeneration generation, CompletionReason reason) {
    generation.complete(reason);
    observability.recordCompletion(reason, generation.getAttemptCount());
  }

  private SignalTaskDraftInput snapshotOf(TaskDraftGeneration generation) {
    return new SignalTaskDraftInput(
        generation.getSignalTitle(),
        generation.getSignalType(),
        generation.getSignalDescription(),
        generation.getSignalImpact(),
        evidenceOf(generation.getSignalEvidence()));
  }

  private List<SignalTaskDraftInput.Evidence> evidenceOf(String evidenceJson) {
    // 역직렬화 실패는 빈 근거로 취급한다. evidence는 프롬프트의 보조 재료라 없어도 생성이 성립하고,
    // 여기서 예외를 던지면 claim은 커밋된 채 실행만 실패해 lease 만료까지 기다리게 된다.
    return json.read(evidenceJson, SignalTaskDraftInput.Evidence[].class)
        .map(Arrays::asList)
        .orElseGet(
            () -> {
              log.warn("minsu 원장 evidence 역직렬화 실패, 빈 근거로 실행합니다");
              return List.of();
            });
  }
}
