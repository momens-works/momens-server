package works.momens.server.signal.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import works.momens.server.minsu.PreparedTaskDraft;
import works.momens.server.minsu.SignalTaskDraftGenerator;
import works.momens.server.minsu.TaskDraft;
import works.momens.server.outbox.OutboxAppender;
import works.momens.server.project.task.CreateTaskCommand;
import works.momens.server.project.task.TaskPriority;
import works.momens.server.project.task.TaskRole;
import works.momens.server.project.task.TaskSnapshot;
import works.momens.server.project.task.TaskWriter;
import works.momens.server.signal.SignalActionResult;
import works.momens.server.signal.SignalReader;

/** convert/dismiss가 CO-6 이벤트 계약대로 outbox를 발행하는지 검증한다(DB 없이 협력 대상 mock). */
class SignalActionExecutorTest {

  private static final UUID SIGNAL_ID = UUID.randomUUID();
  private static final UUID WORKSPACE_ID = UUID.randomUUID();
  private static final UUID PROJECT_ID = UUID.randomUUID();
  private static final UUID USER_ID = UUID.randomUUID();

  private final SignalActionRepository signalActionRepository = mock(SignalActionRepository.class);
  private final TaskWriter taskWriter = mock(TaskWriter.class);
  private final OutboxAppender outboxAppender = mock(OutboxAppender.class);
  private final SignalTaskDraftGenerator taskDraftGenerator = mock(SignalTaskDraftGenerator.class);

  private final SignalActionExecutor executor =
      new SignalActionExecutor(
          signalActionRepository, taskWriter, outboxAppender, taskDraftGenerator);

  @Test
  @DisplayName("convert는 준비된 draft로 task를 생성하고 signal.converted_to_task를 발행한다")
  void convertCreatesTaskAndAppendsConvertedEvent() {
    // 기본값(pm/medium)과 다른 값으로, executor가 하드코딩 없이 전달받은 draft를 그대로 쓰는지 고정한다.
    String title = "쿠폰 실패 안내 개선";
    SignalReader.Snapshot signal =
        new SignalReader.Snapshot(
            SIGNAL_ID, WORKSPACE_ID, PROJECT_ID, "decision", "제목", "설명", "전체 영향");
    UUID taskId = UUID.randomUUID();
    when(taskWriter.create(any())).thenReturn(snapshot(taskId, title, "design", "high"));

    SignalActionResult result =
        executor.convert(signal, USER_ID, prepared(title, TaskRole.DESIGN, TaskPriority.HIGH));

    verify(taskWriter)
        .create(
            CreateTaskCommand.fromSignal(
                PROJECT_ID, WORKSPACE_ID, title, TaskRole.DESIGN, TaskPriority.HIGH, SIGNAL_ID));
    verify(outboxAppender)
        .append(
            WORKSPACE_ID,
            "signal",
            SIGNAL_ID.toString(),
            "signal.converted_to_task",
            Map.of("task_id", taskId.toString()));
    assertThat(result.created()).isTrue();
    assertThat(result.task().id()).isEqualTo(taskId);
  }

  @Test
  @DisplayName("convert는 생성한 task로 원장 적재를 요청한다")
  void convertEnrollsGenerationLedgerWithCreatedTask() {
    SignalReader.Snapshot signal =
        new SignalReader.Snapshot(
            SIGNAL_ID, WORKSPACE_ID, PROJECT_ID, "decision", "제목", "설명", "전체 영향");
    UUID taskId = UUID.randomUUID();
    when(taskWriter.create(any())).thenReturn(snapshot(taskId, "제목", "pm", "medium"));
    PreparedTaskDraft prepared = prepared("제목", TaskRole.PM, TaskPriority.MEDIUM);

    executor.convert(signal, USER_ID, prepared);

    // 적재 여부 판정은 Minsu가 한다. executor는 준비 결과를 그대로 넘길 뿐 비동기 활성 여부를 모른다.
    verify(taskDraftGenerator).enroll(prepared, taskId, WORKSPACE_ID);
  }

  @Test
  @DisplayName("dismiss는 signal.dismissed를 빈 payload로 발행한다")
  void dismissAppendsDismissedEventWithEmptyPayload() {
    SignalReader.Snapshot signal =
        new SignalReader.Snapshot(
            SIGNAL_ID, WORKSPACE_ID, PROJECT_ID, "decision", "제목", "설명", "전체 영향");

    SignalActionResult result = executor.dismiss(signal, USER_ID);

    verify(outboxAppender)
        .append(WORKSPACE_ID, "signal", SIGNAL_ID.toString(), "signal.dismissed", Map.of());
    verifyNoMoreInteractions(taskWriter);
    assertThat(result.created()).isTrue();
    assertThat(result.task()).isNull();
  }

  /** 실제 준비 결과는 Minsu 내부 타입이라 밖에서 만들 수 없다. 여기서는 draft만 담은 대역을 쓴다. */
  private static PreparedTaskDraft prepared(String title, TaskRole role, TaskPriority priority) {
    return new TestPreparedDraft(new TaskDraft(title, role, priority));
  }

  private static TaskSnapshot snapshot(UUID id, String title, String role, String priority) {
    return new TaskSnapshot(
        id,
        WORKSPACE_ID,
        PROJECT_ID,
        null,
        "MOM-0001",
        title,
        null,
        "todo",
        priority,
        role,
        null,
        null,
        Instant.EPOCH,
        Instant.EPOCH);
  }

  private record TestPreparedDraft(TaskDraft draft) implements PreparedTaskDraft {}
}
