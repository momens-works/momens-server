package works.momens.server.mobile.signal.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import works.momens.server.mobile.MobileDraftStatus;
import works.momens.server.signal.SignalActionResult;

@Schema(description = "convert-to-task 처리 결과")
public record ConvertToTaskResponse(Task task, Signal signal) {

  @Schema(description = "convert 결과 작업")
  public record Task(
      @Schema(description = "작업 식별자") UUID id,
      @Schema(description = "제목. generating 동안에도 항상 유효한 draft입니다") String title,
      @Schema(description = "상태", example = "todo") String status,
      @Schema(
              description = MobileDraftStatus.DESCRIPTION,
              allowableValues = {"generating", "ready"},
              example = "generating")
          String draftStatus) {}

  @Schema(name = "ConvertToTaskSignal")
  public record Signal(
      UUID id,
      @Schema(
              description = "처리한 action. path의 하이픈 표기와 달리 underscore 표기입니다.",
              example = "convert_to_task")
          String action) {}

  public static ConvertToTaskResponse from(SignalActionResult result) {
    SignalActionResult.TaskResult task = result.task();
    return new ConvertToTaskResponse(
        new Task(task.id(), task.title(), task.status(), MobileDraftStatus.key(task.draftStatus())),
        new Signal(result.signalId(), result.actionType()));
  }
}
