package works.momens.server.minsu.draft.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import works.momens.server.minsu.PreparedTaskDraft;
import works.momens.server.minsu.SignalTaskDraftInput;
import works.momens.server.minsu.TaskDraft;
import works.momens.server.minsu.draft.config.MinsuAsyncProperties;
import works.momens.server.minsu.draft.config.MinsuConfigStatus;
import works.momens.server.minsu.draft.json.MinsuJson;
import works.momens.server.minsu.draft.ledger.TaskDraftGenerationEnroller;
import works.momens.server.minsu.draft.prompt.SignalTaskDraftPrompt;
import works.momens.server.minsu.llm.LlmClient;
import works.momens.server.minsu.llm.LlmRequest;
import works.momens.server.minsu.llm.LlmResponse;
import works.momens.server.minsu.llm.LlmTimeoutException;
import works.momens.server.minsu.llm.MinsuLlmProperties;
import works.momens.server.minsu.llm.ModelSelection;
import works.momens.server.minsu.llm.ModelSelectionPolicy;
import works.momens.server.project.task.TaskPriority;
import works.momens.server.project.task.TaskRole;

class DefaultSignalTaskDraftGeneratorTest {

  private static final ModelSelection SELECTION =
      new ModelSelection("google", "gemini-3.5-flash-lite", "project", "global");

  /** 동기 경로의 SDK timeout(MOM-0806). 비동기 값과 별도 키다. */
  private static final Duration SYNC_TIMEOUT = Duration.ofSeconds(8);

  /** 이 테스트는 요청 경로만 다루므로 실행 정책 값은 부등식을 만족하기만 하면 된다. */
  private static final MinsuAsyncProperties.Execution EXECUTION =
      new MinsuAsyncProperties.Execution(
          Duration.ofSeconds(20),
          Duration.ofSeconds(30),
          Duration.ofSeconds(60),
          4,
          List.of(Duration.ofSeconds(10)),
          4);

  private final TaskDraftGenerationEnroller enroller = mock(TaskDraftGenerationEnroller.class);

  @BeforeEach
  void stubEnroller() {
    // 실제 적재기는 draft와 snapshot을 봉인한 자기 타입을 돌려준다. 여기서는 draft만 그대로 되비친다.
    when(enroller.bind(any(), any()))
        .thenAnswer(
            invocation -> {
              TaskDraft bound = invocation.getArgument(0);
              return (PreparedTaskDraft) () -> bound;
            });
  }

  @Test
  void returnsValidatedStructuredOutput() {
    CapturingClient client =
        responding(success("{\"title\":\"권한 흐름 점검\",\"role\":\"backend\",\"priority\":\"high\"}"));

    TaskDraft result = generator(client, true, true).prepare(input()).draft();

    assertThat(result).isEqualTo(new TaskDraft("권한 흐름 점검", TaskRole.BACKEND, TaskPriority.HIGH));
    assertThat(client.calls()).isEqualTo(1);
  }

  @Test
  void truncatesTitleWithoutSplittingSurrogatePair() {
    CapturingClient client =
        responding(
            success("{\"title\":\"12345678901234😀끝\",\"role\":\"pm\",\"priority\":\"low\"}"));

    TaskDraft result = generator(client, true, true).prepare(input()).draft();

    assertThat(result.title()).isEqualTo("12345678901234").hasSizeLessThanOrEqualTo(15);
  }

  @Test
  void stripsTrailingSpaceAfterTruncatingTitle() {
    CapturingClient client =
        responding(
            success("{\"title\":\"12345678901234 A\",\"role\":\"pm\",\"priority\":\"low\"}"));

    TaskDraft result = generator(client, true, true).prepare(input()).draft();

    assertThat(result.title()).isEqualTo("12345678901234").doesNotEndWith(" ");
  }

  @Test
  void usesNormalizedSignalTitleWhenModelTitleIsBlank() {
    CapturingClient client =
        responding(success("{\"title\":\"  \",\"role\":\"design\",\"priority\":\"medium\"}"));
    SignalTaskDraftInput input =
        new SignalTaskDraftInput(" 12345678901234567890 ", "risk", "설명", null, List.of());

    TaskDraft result = generator(client, true, true).prepare(input).draft();

    assertThat(result)
        .isEqualTo(new TaskDraft("123456789012345", TaskRole.DESIGN, TaskPriority.MEDIUM));
  }

  @Test
  void recordsTitleFallbackAsGeneratedOutcomeWithoutFallbackReason() {
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    CapturingClient client =
        responding(success("{\"title\":\" \",\"role\":\"design\",\"priority\":\"medium\"}"));

    generator(client, true, true, ObservationRegistry.create(), meterRegistry)
        .prepare(input())
        .draft();

    assertThat(
            meterRegistry
                .get("momens.minsu.task.draft.requests")
                .tag("outcome", "generated_title_fallback")
                .tag("fallback.reason", "none")
                .counter()
                .count())
        .isEqualTo(1);
  }

  @ParameterizedTest
  @MethodSource("invalidEnumResponses")
  void fallsBackWholeDraftForInvalidRoleOrPriority(String response) {
    TaskDraft result =
        generator(responding(success(response)), true, true).prepare(input()).draft();

    assertThat(result).isEqualTo(fallback());
  }

  @ParameterizedTest
  @MethodSource("invalidResponses")
  void fallsBackForInvalidProviderResponse(LlmResponse response) {
    TaskDraft result = generator(responding(response), true, true).prepare(input()).draft();

    assertThat(result).isEqualTo(fallback());
  }

  @ParameterizedTest
  @MethodSource("providerErrors")
  void callsProviderOnceAndFallsBackForProviderErrors(RuntimeException error) {
    CapturingClient client = failing(error);

    TaskDraft result = generator(client, true, true).prepare(input()).draft();

    assertThat(result).isEqualTo(fallback());
    assertThat(client.calls()).isEqualTo(1);
  }

  @Test
  void callsProviderOnceAndRecordsTimeoutFallbackSeparately() {
    RecordingObservationHandler handler = new RecordingObservationHandler();
    ObservationRegistry registry = ObservationRegistry.create();
    registry.observationConfig().observationHandler(handler);
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    CapturingClient client = failing(new LlmTimeoutException(new RuntimeException("timeout")));

    TaskDraft result =
        generator(client, true, true, registry, meterRegistry).prepare(input()).draft();

    assertAll(
        () -> assertThat(result).isEqualTo(fallback()),
        () -> assertThat(client.calls()).isEqualTo(1),
        () ->
            assertThat(
                    meterRegistry
                        .get("momens.minsu.task.draft.requests")
                        .tag("outcome", "fallback")
                        .tag("fallback.reason", "timeout")
                        .counter()
                        .count())
                .isEqualTo(1),
        () ->
            assertThat(
                    handler.stoppedContext.getLowCardinalityKeyValues().stream()
                        .map(keyValue -> keyValue.getKey() + "=" + keyValue.getValue()))
                .contains("outcome=fallback", "fallback.reason=timeout"),
        () ->
            assertThat(handler.stoppedContext.getError()).isInstanceOf(LlmTimeoutException.class));
  }

  @Test
  void doesNotCallProviderWhenDisabled() {
    CapturingClient client = responding(success("{}"));

    TaskDraft result = generator(client, false, true).prepare(input()).draft();

    assertThat(result).isEqualTo(fallback());
    assertThat(client.calls()).isZero();
  }

  @Test
  void doesNotCallProviderWhenAsyncEnrollIsEnabled() {
    // 이 설계의 목적 자체다(5.5절). 비동기 활성인데 요청 경로에서 provider를 부르면 8초 예산이 그대로 남는다.
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    CapturingClient client = responding(success("{}"));

    PreparedTaskDraft prepared =
        generator(client, true, true, true, ObservationRegistry.create(), meterRegistry)
            .prepare(input());

    assertAll(
        () -> assertThat(client.calls()).isZero(),
        () -> assertThat(prepared.draft()).isEqualTo(fallback()),
        // 적재기가 draft와 입력 snapshot을 함께 봉인한다. baseline은 여기서 정해진다(8.1절).
        () -> verify(enroller).bind(fallback(), input()),
        () ->
            assertThat(
                    meterRegistry
                        .get("momens.minsu.task.draft.requests")
                        .tag("outcome", "fallback")
                        .tag("fallback.reason", "async_deferred")
                        .counter()
                        .count())
                .isEqualTo(1));
  }

  @Test
  void enrollsWithTheDraftThatWasWrittenToTasks() {
    UUID taskId = UUID.randomUUID();
    UUID workspaceId = UUID.randomUUID();
    DefaultSignalTaskDraftGenerator generator =
        generator(
            responding(success("{}")),
            true,
            true,
            true,
            ObservationRegistry.create(),
            new SimpleMeterRegistry());

    PreparedTaskDraft prepared = generator.prepare(input());
    generator.enroll(prepared, taskId, workspaceId);

    // 준비 결과를 손대지 않고 그대로 넘긴다. baseline은 그 안에 이미 봉인돼 있다(8.1절).
    verify(enroller).enroll(prepared, taskId, workspaceId);
  }

  @Test
  void doesNotEnrollWhenAsyncIsDisabled() {
    CapturingClient client =
        responding(success("{\"title\":\"점검\",\"role\":\"pm\",\"priority\":\"medium\"}"));
    DefaultSignalTaskDraftGenerator generator = generator(client, true, true);

    PreparedTaskDraft prepared = generator.prepare(input());
    generator.enroll(prepared, UUID.randomUUID(), UUID.randomUUID());

    assertThat(prepared.draft()).isEqualTo(new TaskDraft("점검", TaskRole.PM, TaskPriority.MEDIUM));
    verifyNoInteractions(enroller);
  }

  @Test
  void doesNotEnrollWhenProviderIsDisabledEvenIfEnrollIsOn() {
    // 11.2절의 파생 규칙. 생성할 수단이 없는데 원장만 쌓이면 deadline까지 generating으로 남았다 닫힐 뿐이다.
    CapturingClient client = responding(success("{}"));

    PreparedTaskDraft prepared =
        generator(
                client, false, true, true, ObservationRegistry.create(), new SimpleMeterRegistry())
            .prepare(input());

    assertAll(
        () -> assertThat(prepared.draft()).isEqualTo(fallback()),
        () -> assertThat(client.calls()).isZero(),
        () -> verifyNoInteractions(enroller));
  }

  @Test
  void doesNotCallProviderWhenConfigIsInvalid() {
    CapturingClient client = responding(success("{}"));

    TaskDraft result = generator(client, true, false).prepare(input()).draft();

    assertThat(result).isEqualTo(fallback());
    assertThat(client.calls()).isZero();
  }

  @Test
  void doesNotCallProviderWithoutDescriptionImpactOrEvidence() {
    CapturingClient client = responding(success("{}"));
    SignalTaskDraftInput insufficient =
        new SignalTaskDraftInput("시그널 제목", "risk", " ", null, List.of());

    TaskDraft result = generator(client, true, true).prepare(insufficient).draft();

    assertThat(result).isEqualTo(fallback());
    assertThat(client.calls()).isZero();
  }

  @Test
  void createsAndStopsObservationWithLowCardinalityKeysOnSuccess() {
    RecordingObservationHandler handler = new RecordingObservationHandler();
    ObservationRegistry registry = ObservationRegistry.create();
    registry.observationConfig().observationHandler(handler);
    CapturingClient client =
        responding(success("{\"title\":\"점검\",\"role\":\"pm\",\"priority\":\"medium\"}"));

    generator(client, true, true, registry).prepare(input()).draft();

    assertAll(
        () -> assertThat(handler.started).isEqualTo(1),
        () -> assertThat(handler.stopped).isEqualTo(1),
        () -> assertThat(handler.stoppedContext).isNotNull(),
        () ->
            assertThat(
                    handler.stoppedContext.getLowCardinalityKeyValues().stream()
                        .map(keyValue -> keyValue.getKey() + "=" + keyValue.getValue()))
                .containsExactlyInAnyOrder(
                    "provider=google",
                    "model=gemini-3.5-flash-lite",
                    "prompt.version=signal-task-draft-v1",
                    // 동기 요청 경로와 비동기 실행 경로를 나눠 보기 위한 tag(9.3절).
                    "mode=sync",
                    "outcome=generated",
                    "fallback.reason=none",
                    "finish.reason=stop"));
  }

  @Test
  void stopsObservationAndRecordsErrorWhenProviderThrows() {
    RecordingObservationHandler handler = new RecordingObservationHandler();
    ObservationRegistry registry = ObservationRegistry.create();
    registry.observationConfig().observationHandler(handler);

    generator(failing(new RuntimeException("quota")), true, true, registry)
        .prepare(input())
        .draft();

    assertAll(
        () -> assertThat(handler.started).isEqualTo(1),
        () -> assertThat(handler.stopped).isEqualTo(1),
        () -> assertThat(handler.stoppedContext.getError()).isInstanceOf(RuntimeException.class),
        () ->
            assertThat(handler.stoppedContext.getLowCardinalityKeyValues().stream())
                .noneMatch(keyValue -> keyValue.getKey().contains("signal")));
  }

  @Test
  void recordsSafetyFinishReasonAsInvalidResponseWithTokenUsage() {
    RecordingObservationHandler handler = new RecordingObservationHandler();
    ObservationRegistry registry = ObservationRegistry.create();
    registry.observationConfig().observationHandler(handler);
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    LlmResponse safetyResponse =
        new LlmResponse(
            true, "SAFETY", "", "response-id", new LlmResponse.TokenUsage(10, 7, 3, 20));

    TaskDraft result =
        generator(responding(safetyResponse), true, true, registry, meterRegistry)
            .prepare(input())
            .draft();

    assertAll(
        () -> assertThat(result).isEqualTo(fallback()),
        () ->
            assertThat(
                    meterRegistry
                        .get("momens.minsu.task.draft.requests")
                        .tag("outcome", "fallback")
                        .tag("fallback.reason", "invalid_response")
                        .counter()
                        .count())
                .isEqualTo(1),
        // 허용된 세 type이 모두 기록되는지 값까지 본다. prompt만 보면 candidate·thoughts가 빠져도 통과한다.
        () -> assertThat(tokenAmount(meterRegistry, "prompt")).isEqualTo(10),
        () -> assertThat(tokenAmount(meterRegistry, "candidate")).isEqualTo(7),
        () -> assertThat(tokenAmount(meterRegistry, "thoughts")).isEqualTo(3),
        // total은 나머지를 포함하므로 시계열로 두면 type을 가로지른 합산이 이중 계상된다.
        // 지표 규약(docs/rules/observability.md)이 금지하는 형태라 되살아나지 않게 고정한다.
        () ->
            assertThat(meterRegistry.find("momens.minsu.llm.tokens").tag("type", "total").summary())
                .isNull(),
        () ->
            assertThat(
                    handler.stoppedContext.getLowCardinalityKeyValues().stream()
                        .map(keyValue -> keyValue.getKey() + "=" + keyValue.getValue()))
                .contains(
                    "outcome=fallback",
                    "fallback.reason=invalid_response",
                    "finish.reason=safety"));
  }

  /**
   * 지표 규약의 "0 선등록"(docs/rules/observability.md). 태그 값이 enum이라 조합이 부팅 시점에 모두 정해지므로, 첫 생성 전에도 시계열이
   * 있어야 재시작 직후 구간이 장애와 구분된다.
   *
   * <p>개수만 보면 매핑이 틀려도 조합 수만 같으면 통과하므로 태그 값까지 조회한다. 태그 값은 대시보드·경보가 붙는 계약이라 조용히 바뀌면 안 된다. 개수 검증은 의도치
   * 않게 늘어난 시계열을 잡기 위해 함께 둔다.
   */
  @Test
  void preRegistersRequestCountersForEveryOutcome() {
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    new MinsuObservability(meterRegistry, ObservationRegistry.create());

    assertThat(meterRegistry.find("momens.minsu.task.draft.requests").counters())
        .hasSize(GenerationOutcome.values().length)
        .allSatisfy(counter -> assertThat(counter.count()).isZero());
    assertAll(
        Stream.of(GenerationOutcome.values())
            .map(
                outcome ->
                    () ->
                        assertThat(
                                meterRegistry
                                    .get("momens.minsu.task.draft.requests")
                                    .tag("outcome", outcome.outcome())
                                    .tag("fallback.reason", outcome.reason())
                                    .counter()
                                    .count())
                            .isZero()));
  }

  private static double tokenAmount(SimpleMeterRegistry meterRegistry, String type) {
    return meterRegistry.get("momens.minsu.llm.tokens").tag("type", type).summary().totalAmount();
  }

  @Test
  void logsInvalidResponseAtWarn() {
    List<ILoggingEvent> events =
        captureLogs(
            () ->
                generator(
                        responding(
                            new LlmResponse(
                                true, "SAFETY", "", "response-id", LlmResponse.TokenUsage.EMPTY)),
                        true,
                        true)
                    .prepare(input())
                    .draft());

    assertThat(events)
        .filteredOn(
            event -> event.getFormattedMessage().contains("fallbackReason=invalid_response"))
        .extracting(ILoggingEvent::getLevel)
        .containsExactly(Level.WARN);
  }

  @Test
  void logsInvalidOutputAtWarn() {
    List<ILoggingEvent> events =
        captureLogs(
            () ->
                generator(
                        responding(
                            success(
                                "{\"title\":\"점검\",\"role\":\"invalid\",\"priority\":\"medium\"}")),
                        true,
                        true)
                    .prepare(input())
                    .draft());

    assertThat(events)
        .filteredOn(event -> event.getFormattedMessage().contains("fallbackReason=invalid_output"))
        .extracting(ILoggingEvent::getLevel)
        .containsExactly(Level.WARN);
  }

  @Test
  void logsGeneratedResponseAtDebug() {
    List<ILoggingEvent> events =
        captureLogs(
            () ->
                generator(
                        responding(
                            success("{\"title\":\"점검\",\"role\":\"pm\",\"priority\":\"medium\"}")),
                        true,
                        true)
                    .prepare(input())
                    .draft());

    assertThat(events)
        .filteredOn(event -> event.getFormattedMessage().contains("outcome=generated"))
        .extracting(ILoggingEvent::getLevel)
        .containsExactly(Level.DEBUG);
  }

  private DefaultSignalTaskDraftGenerator generator(
      LlmClient client, boolean enabled, boolean valid) {
    return generator(client, enabled, valid, ObservationRegistry.create());
  }

  private DefaultSignalTaskDraftGenerator generator(
      LlmClient client, boolean enabled, boolean valid, ObservationRegistry observationRegistry) {
    return generator(client, enabled, valid, observationRegistry, new SimpleMeterRegistry());
  }

  private DefaultSignalTaskDraftGenerator generator(
      LlmClient client,
      boolean enabled,
      boolean valid,
      ObservationRegistry observationRegistry,
      SimpleMeterRegistry meterRegistry) {
    return generator(client, enabled, valid, false, observationRegistry, meterRegistry);
  }

  private DefaultSignalTaskDraftGenerator generator(
      LlmClient client,
      boolean enabled,
      boolean valid,
      boolean asyncEnroll,
      ObservationRegistry observationRegistry,
      SimpleMeterRegistry meterRegistry) {
    MinsuConfigStatus config = mock(MinsuConfigStatus.class);
    when(config.enabled()).thenReturn(enabled);
    when(config.valid()).thenReturn(valid);
    ModelSelectionPolicy selectionPolicy = useCase -> SELECTION;
    MinsuJson json = new MinsuJson();
    MinsuObservability observability = new MinsuObservability(meterRegistry, observationRegistry);
    return new DefaultSignalTaskDraftGenerator(
        config,
        new MinsuAsyncProperties(
            asyncEnroll, false, Duration.ofHours(1), Duration.ofMinutes(5), EXECUTION),
        new MinsuLlmProperties("google", "gemini-3.5-flash-lite", SYNC_TIMEOUT, null),
        enroller,
        new TaskDraftAttempt(
            config, selectionPolicy, client, new SignalTaskDraftPrompt(json), json, observability),
        observability);
  }

  private static SignalTaskDraftInput input() {
    return new SignalTaskDraftInput(
        "시그널 제목",
        "risk",
        "사용자가 반복해서 이탈합니다",
        null,
        List.of(new SignalTaskDraftInput.Evidence("권한 화면", "이탈 증가", "완료율 하락")));
  }

  private static TaskDraft fallback() {
    return new TaskDraft("시그널 제목", TaskRole.PM, TaskPriority.MEDIUM);
  }

  private static LlmResponse success(String text) {
    return new LlmResponse(
        true, "STOP", text, "response-id", new LlmResponse.TokenUsage(10, 5, 1, 16));
  }

  private static CapturingClient responding(LlmResponse response) {
    return new CapturingClient(response, null);
  }

  private static CapturingClient failing(RuntimeException error) {
    return new CapturingClient(null, error);
  }

  private static Stream<Arguments> invalidEnumResponses() {
    return Stream.of(
        Arguments.of("{\"title\":\"점검\",\"role\":\"invalid\",\"priority\":\"high\"}"),
        Arguments.of("{\"title\":\"점검\",\"role\":\"pm\",\"priority\":\"critical\"}"),
        Arguments.of("{\"title\":\"점검\",\"role\":\"\",\"priority\":\"medium\"}"),
        Arguments.of("{\"title\":\"점검\",\"role\":\"pm\",\"priority\":\"\"}"));
  }

  private static Stream<LlmResponse> invalidResponses() {
    return Stream.of(
        new LlmResponse(false, "", null, "", LlmResponse.TokenUsage.EMPTY),
        new LlmResponse(true, "MAX_TOKENS", "{}", "", LlmResponse.TokenUsage.EMPTY),
        new LlmResponse(true, "STOP", " ", "", LlmResponse.TokenUsage.EMPTY),
        new LlmResponse(true, "STOP", "{malformed", "", LlmResponse.TokenUsage.EMPTY));
  }

  private static Stream<RuntimeException> providerErrors() {
    return Stream.of(
        new RuntimeException("provider"),
        new RuntimeException("rate limit"),
        new RuntimeException("quota"));
  }

  private static List<ILoggingEvent> captureLogs(Runnable action) {
    Logger logger = (Logger) LoggerFactory.getLogger(TaskDraftAttempt.class);
    Level previousLevel = logger.getLevel();
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.setLevel(Level.DEBUG);
    logger.addAppender(appender);
    try {
      action.run();
      return List.copyOf(appender.list);
    } finally {
      logger.detachAppender(appender);
      logger.setLevel(previousLevel);
    }
  }

  private static final class CapturingClient implements LlmClient {

    private final LlmResponse response;
    private final RuntimeException error;
    private final AtomicInteger calls = new AtomicInteger();

    private CapturingClient(LlmResponse response, RuntimeException error) {
      this.response = response;
      this.error = error;
    }

    @Override
    public LlmResponse generate(ModelSelection selection, LlmRequest request, Duration timeout) {
      calls.incrementAndGet();
      if (error != null) {
        throw error;
      }
      return response;
    }

    int calls() {
      return calls.get();
    }
  }

  private static final class RecordingObservationHandler
      implements ObservationHandler<Observation.Context> {

    private int started;
    private int stopped;
    private Observation.Context stoppedContext;

    @Override
    public void onStart(Observation.Context context) {
      started++;
    }

    @Override
    public void onStop(Observation.Context context) {
      stopped++;
      stoppedContext = context;
    }

    @Override
    public boolean supportsContext(Observation.Context context) {
      return true;
    }
  }
}
