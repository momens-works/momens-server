package works.momens.server.minsu.draft.generation;

import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import works.momens.server.minsu.DraftStatus;
import works.momens.server.minsu.PreparedTaskDraft;
import works.momens.server.minsu.SignalTaskDraftGenerator;
import works.momens.server.minsu.SignalTaskDraftInput;
import works.momens.server.minsu.TaskDraft;
import works.momens.server.minsu.draft.config.MinsuAsyncProperties;
import works.momens.server.minsu.draft.config.MinsuConfigStatus;
import works.momens.server.minsu.draft.ledger.TaskDraftGenerationEnroller;
import works.momens.server.minsu.llm.MinsuLlmProperties;

/**
 * Minsu 공개 계약 구현.
 *
 * <p>비동기 활성 판정을 이 클래스가 소유한다. 판정은 {@code prepare}에서 한 번만 하고 그 결과를 {@link PreparedTaskDraft}에 담아
 * 내보내므로, 한 요청 안에서 판정이 두 번 갈릴 수 없다.
 *
 * <p>실제 생성은 {@link TaskDraftAttempt}가 하고 이 클래스는 경로 선택만 한다. 같은 시도를 비동기 실행({@link
 * AsyncTaskDraftExecutor})도 쓰므로 프롬프트·검증·정규화가 두 경로에서 갈라지지 않는다.
 *
 * <p>{@code @Transactional} 프록시가 걸리므로 {@code final}일 수 없다.
 */
@Slf4j
@Component
class DefaultSignalTaskDraftGenerator implements SignalTaskDraftGenerator {

  private final MinsuConfigStatus configStatus;
  private final MinsuAsyncProperties asyncProperties;
  private final MinsuLlmProperties llmProperties;
  private final TaskDraftGenerationEnroller enroller;
  private final TaskDraftAttempt attempt;
  private final MinsuObservability observability;

  DefaultSignalTaskDraftGenerator(
      MinsuConfigStatus configStatus,
      MinsuAsyncProperties asyncProperties,
      MinsuLlmProperties llmProperties,
      TaskDraftGenerationEnroller enroller,
      TaskDraftAttempt attempt,
      MinsuObservability observability) {
    this.configStatus = configStatus;
    this.asyncProperties = asyncProperties;
    this.llmProperties = llmProperties;
    this.enroller = enroller;
    this.attempt = attempt;
    this.observability = observability;
  }

  @Override
  public PreparedTaskDraft prepare(SignalTaskDraftInput input) {
    TaskDraft fallback = TaskDraftAttempt.fallbackOf(input);
    if (!configStatus.enabled()) {
      // provider가 꺼져 있으면 enroll 값과 무관하게 적재하지 않는다. 생성할 수단이 없는 원장은
      // deadline까지 generating으로 남았다 닫힐 뿐이다.
      return synchronous(finish(fallback, GenerationOutcome.DISABLED));
    }
    if (asyncProperties.enroll()) {
      // 비동기 활성 경로. 여기서 LLM을 부르면 이 설계의 목적 자체가 사라진다.
      return enroller.bind(finish(fallback, GenerationOutcome.DEFERRED), input);
    }
    TaskDraftAttempt.Result result =
        attempt.run(input, GenerationMode.SYNCHRONOUS, llmProperties.timeout());
    return synchronous(finish(result.draft(), result.outcome()));
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public DraftStatus enroll(PreparedTaskDraft prepared, UUID taskId, UUID workspaceId) {
    // 동기 경로의 no-op에도 MANDATORY가 걸리는 것이 의도다. 여기서만 강제하면 위반이 비동기를 켠
    // 환경에서 처음 드러난다. 계약 위반은 설정과 무관하게 같은 자리에서 드러나야 한다.
    if (prepared instanceof SynchronousDraft) {
      return DraftStatus.READY;
    }
    // 우리가 만들지 않은 준비 결과는 적재기가 거부한다. 조용히 넘기면 적재되지 않은 채 ready가 된다.
    enroller.enroll(prepared, taskId, workspaceId);
    // 적재에 성공한 행만 여기 도달한다(실패는 예외로 호출자 트랜잭션을 롤백시킨다). 따라서 이 값은
    // 커밋될 원장의 상태와 어긋날 수 없다.
    return DraftStatus.GENERATING;
  }

  private static PreparedTaskDraft synchronous(TaskDraft draft) {
    return new SynchronousDraft(draft);
  }

  private TaskDraft finish(TaskDraft draft, GenerationOutcome outcome) {
    observability.recordRequest(outcome);
    return draft;
  }
}
