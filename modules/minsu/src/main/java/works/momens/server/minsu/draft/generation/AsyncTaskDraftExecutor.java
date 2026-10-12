package works.momens.server.minsu.draft.generation;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;
import works.momens.server.minsu.SignalTaskDraftInput;
import works.momens.server.minsu.draft.config.MinsuAsyncProperties;
import works.momens.server.minsu.draft.config.MinsuAsyncProperties.Execution;

/**
 * 비동기 경로의 실행기.
 *
 * <p>SDK timeout만으로는 부족하다. {@code HttpOptions.timeout}은 OkHttp {@code Call} 구간에만 걸리고 ADC 탐색·client
 * 생성·token refresh는 그 밖이라, 전단에서 멈춘 호출은 아무도 기다리지 않는 비동기에서 그대로 남는다. 그래서 <b>시도 전체를 감싸는 wall-clock
 * 상한</b>을 둔다. 상한을 넘기면 결과를 버리고 {@link GenerationOutcome#TIMEOUT}으로 판정해 원장이 재시도할 수 있게 한다.
 *
 * <p><b>이 상한이 호출을 실제로 취소하지는 못한다.</b> {@code cancel(true)}가 보내는 interrupt에 SDK나 ADC 호출이 응답하지 않으면
 * underlying thread는 계속 점유된다. {@code GoogleGenAiLlmClient}는 동기 {@code generateContent}를 호출하고 최초
 * client 생성을 {@code synchronized} 블록에서 수행하므로, 첫 생성이 멈추면 뒤따르는 호출도 같은 monitor에서 막힐 수 있다.
 *
 * <p>따라서 <b>포화 가능성을 수용한다.</b> 취소 가능한 transport로 바꾸거나 시도를 별도 프로세스로 격리하는 방안은 이 슬라이스를 크게 넘고, 얻는 것은 이미
 * 이상 상황인 경우의 복구 시간 단축뿐이다. 대신 포화를 복구할 수 있게 한다. 원장이 durable하므로 Pod를 재시작하면 {@code pending}과 lease 만료분을
 * 그대로 이어서 처리하고 유실은 없다.
 *
 * <p>{@link #availableSlots()}를 밖으로 내보내는 이유가 여기에 있다. 호출자는 빈 슬롯 수만큼만 claim해야 한다. 더 집으면 실행되지 못한 작업이
 * 상한을 그냥 흘려보내 시도 횟수만 소모한다. 슬롯이 비어 있는 만큼만 제출하므로 제출된 시도는 모두 즉시 시작하고, 배치 단위 대기가 곧 시도별 상한이 된다.
 */
@Slf4j
@Component
public class AsyncTaskDraftExecutor implements DisposableBean {

  private final TaskDraftAttempt attempt;
  private final MinsuAsyncProperties asyncProperties;
  private final ThreadPoolExecutor pool;

  /**
   * 상한을 넘겼는데도 아직 반환하지 않은 시도 수.
   *
   * <p>{@link #availableSlots()}가 {@code activeCount} 기반이라 이 값이 곧 <b>영구히 잃은 슬롯 수</b>다. 동시성 설정에 도달하면
   * claim이 완전히 멈춘다. 시도 횟수 분포만 보면 이 상태가 재시도처럼 보이므로 둘을 같이 봐야 한다.
   */
  private final AtomicInteger hungAttempts = new AtomicInteger();

  AsyncTaskDraftExecutor(
      TaskDraftAttempt attempt, MinsuAsyncProperties asyncProperties, MeterRegistry meterRegistry) {
    this.attempt = attempt;
    this.asyncProperties = asyncProperties;
    int concurrency = asyncProperties.execution().concurrency();
    this.pool =
        new ThreadPoolExecutor(
            concurrency,
            concurrency,
            0L,
            TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(),
            runnable -> {
              Thread thread = new Thread(runnable, "minsu-draft-drain");
              thread.setDaemon(true);
              return thread;
            });
    // active·queued·completed·queue·pool 상태를 Micrometer 표준 이름으로 낸다. 직접 gauge를 만들면
    // 이름만 우리 것이 되고 의미는 같아진다. monitor()가 아니라 bindTo()를 쓰는 이유는 전자가 실행기를
    // 감싼 ExecutorService를 돌려주기 때문이다. 이 클래스는 ThreadPoolExecutor의 activeCount로 빈
    // 슬롯을 세므로 래퍼로 바꾸면 availableSlots()가 성립하지 않는다.
    new ExecutorServiceMetrics(pool, "minsu-draft-drain", Tags.empty()).bindTo(meterRegistry);
    Gauge.builder("momens.minsu.drain.hung.attempts", hungAttempts, AtomicInteger::get)
        .register(meterRegistry);
  }

  /** 지금 즉시 시작할 수 있는 시도 수. 멈춘 호출이 점유한 슬롯은 여기서 빠진다. */
  public int availableSlots() {
    return Math.max(pool.getMaximumPoolSize() - pool.getActiveCount(), 0);
  }

  /**
   * 시도를 병렬로 실행하고 입력과 같은 순서로 결과를 돌려준다. 호출자는 {@link #availableSlots()} 이하만 넘겨야 한다.
   *
   * <p>상한을 넘긴 시도는 {@code TIMEOUT}으로 판정한다. 원장은 이를 retryable로 보고 백오프 뒤 다시 집는다.
   */
  public List<AsyncGenerationResult> executeAll(List<SignalTaskDraftInput> inputs) {
    Execution execution = asyncProperties.execution();
    List<Future<TaskDraftAttempt.Result>> futures = new ArrayList<>(inputs.size());
    List<AtomicBoolean> settled = new ArrayList<>(inputs.size());
    for (SignalTaskDraftInput input : inputs) {
      // 시도 하나의 결말을 실행 스레드와 대기 스레드 중 <b>먼저 도달한 쪽</b>이 가져간다. 이 합의가
      // 없으면 상한과 정상 종료가 겹칠 때 hung 수가 늘기만 하고 줄지 않는다.
      AtomicBoolean attemptSettled = new AtomicBoolean();
      settled.add(attemptSettled);
      futures.add(
          pool.submit(
              () -> {
                try {
                  return attempt.run(
                      input, GenerationMode.ASYNCHRONOUS, execution.providerTimeout());
                } finally {
                  if (!attemptSettled.compareAndSet(false, true)) {
                    // 대기 쪽이 먼저 상한으로 포기한 시도가 뒤늦게 돌아왔다. 슬롯이 회수된다.
                    hungAttempts.decrementAndGet();
                  }
                }
              }));
    }
    long startedAtNanos = System.nanoTime();
    long deadlineNanos = startedAtNanos + execution.attemptTimeout().toNanos();
    List<AsyncGenerationResult> results = new ArrayList<>(inputs.size());
    for (int i = 0; i < futures.size(); i++) {
      results.add(
          await(futures.get(i), inputs.get(i), settled.get(i), startedAtNanos, deadlineNanos));
    }
    return results;
  }

  private AsyncGenerationResult await(
      Future<TaskDraftAttempt.Result> future,
      SignalTaskDraftInput input,
      AtomicBoolean settled,
      long startedAtNanos,
      long deadlineNanos) {
    try {
      TaskDraftAttempt.Result result =
          future.get(Math.max(deadlineNanos - System.nanoTime(), 0), TimeUnit.NANOSECONDS);
      return new AsyncGenerationResult(result.draft(), result.outcome());
    } catch (TimeoutException e) {
      // interrupt를 무시하는 호출이면 이 취소는 효과가 없고 슬롯은 계속 점유된다. 그래도 호출자는 대기에서
      // 풀려나 원장에 결과를 남길 수 있다.
      future.cancel(true);
      markHungIfUnsettled(settled);
      // 기준은 제출 시각이다. deadline을 기준으로 재면 초과분(거의 0)이 찍혀 상한 값을 조정할 때
      // 근거가 되지 못한다.
      log.warn(
          "Minsu 비동기 시도가 wall-clock 상한을 넘겨 결과를 버립니다 durationMs={}", elapsedMillis(startedAtNanos));
      return timedOut(input);
    } catch (InterruptedException e) {
      // 종료 중이다. 원장은 processing으로 남고 lease 만료 후 다음 인스턴스가 회수한다.
      Thread.currentThread().interrupt();
      future.cancel(true);
      markHungIfUnsettled(settled);
      return timedOut(input);
    } catch (ExecutionException e) {
      // TaskDraftAttempt는 실패를 outcome으로 돌려주므로 여기까지 오는 것은 예상하지 못한 오류다.
      // 재시도 가능한 것으로 보고 원장에 맡긴다.
      log.error("Minsu 비동기 시도가 예기치 못한 예외로 끝났습니다", e.getCause());
      return new AsyncGenerationResult(
          TaskDraftAttempt.fallbackOf(input), GenerationOutcome.PROVIDER_ERROR);
    }
  }

  /** 시도가 아직 끝나지 않았을 때만 hung으로 센다. 이미 끝났다면 실행 쪽이 결말을 가져갔으므로 셀 것이 없다. */
  private void markHungIfUnsettled(AtomicBoolean settled) {
    if (settled.compareAndSet(false, true)) {
      hungAttempts.incrementAndGet();
    }
  }

  private static AsyncGenerationResult timedOut(SignalTaskDraftInput input) {
    return new AsyncGenerationResult(TaskDraftAttempt.fallbackOf(input), GenerationOutcome.TIMEOUT);
  }

  private static long elapsedMillis(long startedAtNanos) {
    return Duration.ofNanos(System.nanoTime() - startedAtNanos).toMillis();
  }

  /**
   * 종료 시 멈춘 호출을 기다리지 않는다.
   *
   * <p>{@code shutdownNow}가 보내는 interrupt를 무시하는 호출이 있어도 daemon thread라 JVM 종료를 막지 않는다. 진행 중이던 작업은
   * 원장에 {@code processing}으로 남아 lease 만료 후 회수된다. 종료 시점에 유실되는 것은 실행 중이던 시도뿐이고 원장은 그대로다.
   */
  @Override
  public void destroy() {
    pool.shutdownNow();
  }
}
