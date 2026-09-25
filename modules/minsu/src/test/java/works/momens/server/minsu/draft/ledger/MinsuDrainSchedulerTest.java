package works.momens.server.minsu.draft.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import works.momens.server.minsu.SignalTaskDraftInput;
import works.momens.server.minsu.TaskDraft;
import works.momens.server.minsu.draft.config.MinsuConfigStatus;
import works.momens.server.minsu.draft.generation.AsyncGenerationResult;
import works.momens.server.minsu.draft.generation.AsyncTaskDraftExecutor;
import works.momens.server.minsu.draft.generation.GenerationOutcome;
import works.momens.server.project.task.TaskPriority;
import works.momens.server.project.task.TaskRole;

/** drain 주기의 게이트와 짝짓기를 검증한다(MOM-0819, 설계 9.1·11.2절). */
class MinsuDrainSchedulerTest {

  private final MinsuConfigStatus configStatus = mock(MinsuConfigStatus.class);
  private final TaskDraftGenerationLedger ledger = mock(TaskDraftGenerationLedger.class);
  private final AsyncTaskDraftExecutor executor = mock(AsyncTaskDraftExecutor.class);
  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private final MinsuDrainScheduler scheduler =
      new MinsuDrainScheduler(configStatus, ledger, executor, meterRegistry);

  @Test
  @DisplayName("provider가 비활성이면 claim하지 않는다")
  void doesNotClaimWhenProviderDisabled() {
    when(configStatus.enabled()).thenReturn(false);

    scheduler.drain();

    // 원장은 pending 그대로 남는다. 종료로 기록하면 설정을 되돌려도 되살릴 방법이 없다(11.2절).
    verifyNoInteractions(ledger);
  }

  @Test
  @DisplayName("설정이 무효하면 claim하지 않는다")
  void doesNotClaimWhenConfigInvalid() {
    when(configStatus.enabled()).thenReturn(true);
    when(configStatus.valid()).thenReturn(false);

    scheduler.drain();

    // 이 규칙이 9.2절의 invalid_config 판정보다 앞선다. claim이 없으므로 그 outcome 자체가 생기지 않는다.
    verifyNoInteractions(ledger);
  }

  @Test
  @DisplayName("빈 슬롯이 없으면 claim하지 않는다")
  void doesNotClaimWithoutFreeSlots() {
    enabled();
    when(executor.availableSlots()).thenReturn(0);

    scheduler.drain();

    // 멈춘 호출이 슬롯을 점유한 상태다. 여기서 집으면 실행되지 못한 작업이 시도 횟수만 소모한다(9.1절).
    verify(ledger, never()).claimDue(anyInt());
  }

  @Test
  @DisplayName("빈 슬롯 수만큼만 claim한다")
  void claimsUpToFreeSlots() {
    enabled();
    when(executor.availableSlots()).thenReturn(2);
    when(ledger.claimDue(2)).thenReturn(List.of());

    scheduler.drain();

    verify(ledger).claimDue(2);
    verify(executor, never()).executeAll(any());
  }

  @Test
  @DisplayName("실행 결과를 claim과 같은 순서로 짝지어 기록한다")
  void recordsResultsPairedWithClaims() {
    enabled();
    when(executor.availableSlots()).thenReturn(2);
    ClaimedGeneration first = claim("첫째");
    ClaimedGeneration second = claim("둘째");
    when(ledger.claimDue(2)).thenReturn(List.of(first, second));
    AsyncGenerationResult firstResult = result(GenerationOutcome.GENERATED);
    AsyncGenerationResult secondResult = result(GenerationOutcome.TIMEOUT);
    when(executor.executeAll(List.of(first.input(), second.input())))
        .thenReturn(List.of(firstResult, secondResult));

    scheduler.drain();

    // 순서가 어긋나면 다른 task의 결과가 기록되고, 그 뒤로는 무엇이 틀렸는지 알 방법이 없다.
    InOrder order = inOrder(ledger);
    order.verify(ledger).record(first, firstResult);
    order.verify(ledger).record(second, secondResult);
  }

  @Test
  @DisplayName("주기 안에서 터진 예외를 삼켜 다음 주기를 막지 않는다")
  void swallowsFailureToKeepPolling() {
    enabled();
    when(executor.availableSlots()).thenReturn(1);
    when(ledger.claimDue(1)).thenThrow(new IllegalStateException("DB 연결 실패"));

    // 예외가 올라가면 fixedDelay 스케줄러는 그 자리에서 멈춘다. 그러면 원장만 쌓이고 아무도 처리하지
    // 않는데, 그 상태는 설정을 끈 것과 구분되지 않는다.
    assertThatCode(scheduler::drain).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("실패한 주기는 heartbeat를 갱신하지 않는다")
  void doesNotRefreshHeartbeatOnFailedPass() throws InterruptedException {
    enabled();
    when(executor.availableSlots()).thenReturn(1);
    when(ledger.claimDue(1)).thenThrow(new IllegalStateException("DB 연결 실패"));
    Thread.sleep(20);
    double beforeFailure = heartbeatAge();

    scheduler.drain();

    // catch에서도 갱신하면 매 주기 실패하는 상태가 정상으로 보인다. 나이가 계속 자라야 정지가 드러난다.
    assertThat(heartbeatAge()).isGreaterThan(beforeFailure);
  }

  @Test
  @DisplayName("성공한 주기는 heartbeat를 되돌린다")
  void refreshesHeartbeatOnSuccessfulPass() throws InterruptedException {
    enabled();
    when(executor.availableSlots()).thenReturn(1);
    when(ledger.claimDue(1)).thenReturn(List.of());
    Thread.sleep(20);
    double beforeSuccess = heartbeatAge();

    scheduler.drain();

    assertThat(heartbeatAge()).isLessThan(beforeSuccess);
  }

  @Test
  @DisplayName("drain 축이 꺼져 있으면 빈 자체가 등록되지 않고, 켜면 push 설정과 무관하게 등록된다")
  void registrationFollowsDrainAxisOnly() {
    ApplicationContextRunner runner =
        new ApplicationContextRunner()
            .withBean(MinsuConfigStatus.class, () -> configStatus)
            .withBean(TaskDraftGenerationLedger.class, () -> ledger)
            .withBean(AsyncTaskDraftExecutor.class, () -> executor)
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .withUserConfiguration(MinsuDrainScheduler.class);

    runner
        .withPropertyValues("momens.minsu.task-draft.async.drain=false")
        .run(context -> assertThat(context).doesNotHaveBean(MinsuDrainScheduler.class));
    runner.run(context -> assertThat(context).doesNotHaveBean(MinsuDrainScheduler.class));
    // 스케줄링 인프라는 app이 항상 켜므로(MOM-0816) push를 꺼도 minsu drain은 자신의 축만 따른다.
    runner
        .withPropertyValues(
            "momens.minsu.task-draft.async.drain=true", "momens.notification.push.enabled=false")
        .run(context -> assertThat(context).hasSingleBean(MinsuDrainScheduler.class));
  }

  private double heartbeatAge() {
    return meterRegistry.get("momens.minsu.drain.heartbeat.age").gauge().value();
  }

  private void enabled() {
    when(configStatus.enabled()).thenReturn(true);
    when(configStatus.valid()).thenReturn(true);
  }

  private static ClaimedGeneration claim(String title) {
    return new ClaimedGeneration(
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        new SignalTaskDraftInput(title, "risk", "카드 결제 실패가 늘었다", null, List.of()));
  }

  private static AsyncGenerationResult result(GenerationOutcome outcome) {
    return new AsyncGenerationResult(
        new TaskDraft("결제 대응", TaskRole.PM, TaskPriority.MEDIUM), outcome);
  }
}
