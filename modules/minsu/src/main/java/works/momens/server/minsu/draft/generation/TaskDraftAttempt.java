package works.momens.server.minsu.draft.generation;

import io.micrometer.observation.Observation;
import java.time.Duration;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.stereotype.Component;
import works.momens.server.minsu.SignalTaskDraftInput;
import works.momens.server.minsu.TaskDraft;
import works.momens.server.minsu.draft.config.MinsuConfigStatus;
import works.momens.server.minsu.draft.json.MinsuJson;
import works.momens.server.minsu.draft.prompt.SignalTaskDraftPrompt;
import works.momens.server.minsu.llm.LlmClient;
import works.momens.server.minsu.llm.LlmRequest;
import works.momens.server.minsu.llm.LlmResponse;
import works.momens.server.minsu.llm.LlmTimeoutException;
import works.momens.server.minsu.llm.LlmUseCase;
import works.momens.server.minsu.llm.ModelSelection;
import works.momens.server.minsu.llm.ModelSelectionPolicy;
import works.momens.server.project.task.TaskPriority;
import works.momens.server.project.task.TaskRole;

/**
 * 한 번의 생성 시도. 동기 요청 경로와 비동기 실행 경로가 <b>같은</b> 코드를 쓴다.
 *
 * <p>두 경로가 프롬프트·검증·정규화를 공유해야 하는 이유는 결과의 형태가 같아야 하기 때문이다. 갈라지면 비동기로 전환하는 것만으로 draft의 품질 특성이 달라지고, 그
 * 차이는 설정 축을 켜고 끌 때마다 나타난다. 다르게 두는 것은 timeout과 관측 tag뿐이다({@link GenerationMode}).
 *
 * <p>실패를 예외로 올리지 않고 {@link Result}로 돌려주는 것도 공유의 조건이다. 동기 호출자는 항상 유효한 draft가 필요하고 비동기 호출자는 재시도 판정이
 * 필요한데, 둘을 동시에 만족시키는 형태가 draft와 outcome을 함께 돌려주는 것이다(9.2절).
 */
@Slf4j
@Component
class TaskDraftAttempt {

  private final MinsuConfigStatus configStatus;
  private final ModelSelectionPolicy selectionPolicy;
  private final LlmClient llmClient;
  private final SignalTaskDraftPrompt prompt;
  private final MinsuJson json;
  private final MinsuObservability observability;

  TaskDraftAttempt(
      MinsuConfigStatus configStatus,
      ModelSelectionPolicy selectionPolicy,
      LlmClient llmClient,
      SignalTaskDraftPrompt prompt,
      MinsuJson json,
      MinsuObservability observability) {
    this.configStatus = configStatus;
    this.selectionPolicy = selectionPolicy;
    this.llmClient = llmClient;
    this.prompt = prompt;
    this.json = json;
    this.observability = observability;
  }

  /**
   * convert 시점 입력만으로 정해지는 고정 draft.
   *
   * <p>비동기 경로에서는 이 값이 그대로 {@code tasks}에 쓰이고 원장의 반영 baseline이 된다(8.1절). 어떤 경로로 끝나든 사용자가 보는 것은 최소한 이
   * draft이므로, 비동기 전환은 가용성의 하한을 내리지 않는다(8.5절).
   */
  static TaskDraft fallbackOf(SignalTaskDraftInput input) {
    return new TaskDraft(
        TaskTitleNormalizer.normalize(input.title()), TaskRole.PM, TaskPriority.MEDIUM);
  }

  Result run(SignalTaskDraftInput input, GenerationMode mode, Duration timeout) {
    TaskDraft fallback = fallbackOf(input);
    if (!configStatus.valid()) {
      return new Result(fallback, GenerationOutcome.INVALID_CONFIG);
    }
    if (!SignalTaskDraftPrompt.hasSufficientContext(input)) {
      return new Result(fallback, GenerationOutcome.INSUFFICIENT_CONTEXT);
    }

    ModelSelection selection = selectionPolicy.select(LlmUseCase.SIGNAL_TASK_DRAFT);
    LlmRequest request = prompt.render(input);
    Observation observation = observability.startProvider(selection, request.promptVersion(), mode);
    long startedAt = System.nanoTime();
    try {
      LlmResponse response = llmClient.generate(selection, request, timeout);
      observability.recordTokens(selection, response.tokenUsage());
      Result result = validate(response, input, fallback);
      observability.completeProvider(observation, result.outcome(), response.finishReason(), null);
      logResult(selection, response, result.outcome(), startedAt);
      return result;
    } catch (RuntimeException e) {
      GenerationOutcome outcome =
          e instanceof LlmTimeoutException
              ? GenerationOutcome.TIMEOUT
              : GenerationOutcome.PROVIDER_ERROR;
      observability.completeProvider(observation, outcome, null, e);
      log.warn(
          "Minsu LLM 호출 실패 mode={} provider={} model={} durationMs={} outcome={} fallbackReason={}",
          mode.tag(),
          selection.provider(),
          selection.model(),
          elapsedMillis(startedAt),
          outcome.outcome(),
          outcome.reason());
      return new Result(fallback, outcome);
    }
  }

  private Result validate(LlmResponse response, SignalTaskDraftInput input, TaskDraft fallback) {
    if (!response.candidatePresent()
        || !"STOP".equalsIgnoreCase(response.finishReason())
        || response.text() == null
        || response.text().isBlank()) {
      return new Result(fallback, GenerationOutcome.INVALID_RESPONSE);
    }

    GeneratedDraft generated = json.read(response.text(), GeneratedDraft.class).orElse(null);
    if (generated == null) {
      return new Result(fallback, GenerationOutcome.INVALID_RESPONSE);
    }

    TaskRole role = TaskRole.from(normalizeEnum(generated.role())).orElse(null);
    TaskPriority priority = TaskPriority.from(normalizeEnum(generated.priority())).orElse(null);
    if (role == null || priority == null) {
      return new Result(fallback, GenerationOutcome.INVALID_OUTPUT);
    }

    String rawTitle = generated.title();
    boolean titleFallback = rawTitle == null || rawTitle.isBlank();
    String title = TaskTitleNormalizer.normalize(titleFallback ? input.title() : rawTitle);
    boolean truncated = !titleFallback && rawTitle.trim().length() > TaskTitleNormalizer.MAX_LENGTH;
    GenerationOutcome outcome =
        titleFallback
            ? GenerationOutcome.GENERATED_TITLE_FALLBACK
            : truncated ? GenerationOutcome.GENERATED_TRUNCATED : GenerationOutcome.GENERATED;
    return new Result(new TaskDraft(title, role, priority), outcome);
  }

  private void logResult(
      ModelSelection selection, LlmResponse response, GenerationOutcome outcome, long startedAt) {
    LoggingEventBuilder event =
        outcome == GenerationOutcome.INVALID_RESPONSE || outcome == GenerationOutcome.INVALID_OUTPUT
            ? log.atWarn()
            : log.atDebug();
    event.log(
        "Minsu LLM 호출 완료 provider={} model={} finishReason={} promptTokens={} candidateTokens={} "
            + "thoughtsTokens={} totalTokens={} responseId={} durationMs={} outcome={} "
            + "fallbackReason={}",
        selection.provider(),
        selection.model(),
        response.finishReason(),
        response.tokenUsage().prompt(),
        response.tokenUsage().candidate(),
        response.tokenUsage().thoughts(),
        response.tokenUsage().total(),
        response.responseId(),
        elapsedMillis(startedAt),
        outcome.outcome(),
        outcome.reason());
  }

  private static String normalizeEnum(String value) {
    return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
  }

  private static long elapsedMillis(long startedAt) {
    return (System.nanoTime() - startedAt) / 1_000_000;
  }

  private record GeneratedDraft(String title, String role, String priority) {}

  record Result(TaskDraft draft, GenerationOutcome outcome) {}
}
