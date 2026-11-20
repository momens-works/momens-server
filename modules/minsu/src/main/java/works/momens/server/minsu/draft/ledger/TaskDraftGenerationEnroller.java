package works.momens.server.minsu.draft.ledger;

import java.time.Instant;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import works.momens.server.minsu.PreparedTaskDraft;
import works.momens.server.minsu.SignalTaskDraftInput;
import works.momens.server.minsu.TaskDraft;
import works.momens.server.minsu.TaskDraftEnrollmentException;
import works.momens.server.minsu.draft.config.MinsuAsyncProperties;
import works.momens.server.minsu.draft.json.MinsuJson;

/**
 * 생성 원장 적재.
 *
 * <p>호출자(convert)의 쓰기 트랜잭션 안에서 실행된다. 원장 insert는 {@code tasks}·{@code signal_actions}와 <b>같은</b>
 * 트랜잭션이어야 하고, 별도 경계를 만들면 롤백이 갈려 fail-closed가 깨진다. 활성 트랜잭션 요구는 공개 진입점인 {@link
 * works.momens.server.minsu.SignalTaskDraftGenerator#enroll}의 {@code MANDATORY}가 강제하므로 여기에는 전파 속성을
 * 두지 않는다.
 */
@Component
public class TaskDraftGenerationEnroller {

  private final TaskDraftGenerationRepository repository;
  private final MinsuAsyncProperties asyncProperties;
  private final MinsuJson json;
  private final MinsuLedgerObservability observability;

  TaskDraftGenerationEnroller(
      TaskDraftGenerationRepository repository,
      MinsuAsyncProperties asyncProperties,
      MinsuJson json,
      MinsuLedgerObservability observability) {
    this.repository = repository;
    this.asyncProperties = asyncProperties;
    this.json = json;
    this.observability = observability;
  }

  /** draft 확보 시점에 draft와 입력 snapshot을 함께 봉인한다. 해석은 {@link #enroll}만 한다. */
  public PreparedTaskDraft bind(TaskDraft draft, SignalTaskDraftInput snapshot) {
    return new LedgerBoundDraft(draft, snapshot);
  }

  /**
   * {@code pending} 원장을 적재한다. baseline은 준비 결과가 들고 있는 draft이고, 호출자가 그것을 {@code tasks}에 썼다는 것이 계약이다.
   *
   * <p>{@code saveAndFlush}로 즉시 flush하는 이유는 실패를 <b>이 호출 안에서</b> 잡기 위해서다. 지연 flush에 맡기면 제약 위반이 커밋
   * 시점에 터져 호출자의 다른 제약 위반과 구분되지 않는다.
   */
  public void enroll(PreparedTaskDraft prepared, UUID taskId, UUID workspaceId) {
    if (!(prepared instanceof LedgerBoundDraft bound)) {
      throw new IllegalArgumentException(
          "Minsu가 발급하지 않은 준비 결과입니다: " + prepared.getClass().getName());
    }
    TaskDraft baseline = bound.draft();
    SignalTaskDraftInput snapshot = bound.snapshot();
    try {
      // 시각 조회도 DB 호출이므로 같은 try 안에 둔다. 밖에 두면 이 실패만 전용 예외를 벗어난다.
      Instant enrolledAt = repository.currentDatabaseTime();
      Instant readDeadline = enrolledAt.plus(asyncProperties.generationDeadline());
      repository.saveAndFlush(
          TaskDraftGeneration.builder()
              .workspaceId(workspaceId)
              .taskId(taskId)
              .signalTitle(snapshot.title())
              .signalType(snapshot.type())
              .signalDescription(snapshot.description())
              .signalImpact(snapshot.impact())
              .signalEvidence(json.write(snapshot.evidence()))
              .baselineTitle(baseline.title())
              .baselineRole(baseline.role().value())
              .baselinePriority(baseline.priority().value())
              .readDeadlineAt(readDeadline)
              .applyCutoffAt(readDeadline.minus(asyncProperties.applyMargin()))
              // 적재 직후부터 처리 대상이다. 첫 시도의 대기는 백오프가 아니라 scheduler 주기다.
              .nextAttemptAt(enrolledAt)
              .build());
      observability.recordEnrollment(true);
    } catch (DataAccessException e) {
      observability.recordEnrollment(false);
      throw new TaskDraftEnrollmentException("Minsu 생성 원장 적재에 실패했습니다: taskId=" + taskId, e);
    }
  }
}
