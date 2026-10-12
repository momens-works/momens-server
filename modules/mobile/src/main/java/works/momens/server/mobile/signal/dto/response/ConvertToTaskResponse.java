package works.momens.server.mobile.signal.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import works.momens.server.mobile.MobileDraftStatus;
import works.momens.server.signal.SignalActionResult;

/**
 * {@code POST /api/mobile/signals/{signalId}/actions/convert-to-task} 응답. 형식은
 * docs/spec/openapi.json에서 확인합니다.
 *
 * <p>새로 처리됐으면 201, 같은 action 재요청(멱등 replay)이면 200으로 응답하되 body shape는 같습니다. {@code signal.action}은
 * ledger 값 그대로 underscore 표기({@code convert_to_task})입니다.
 */
@Schema(description = "convert-to-task 처리 결과")
public record ConvertToTaskResponse(Task task, Signal signal) {

  @Schema(description = "convert 결과 작업")
  public record Task(
      @Schema(description = "작업 식별자") UUID id,
      @Schema(description = "제목. generating 동안에도 항상 유효한 draft입니다") String title,
      @Schema(description = "상태", example = "todo") String status,
      @Schema(
              description =
                  "AI가 제목을 더 손볼 것이 남았는지. generating이면 나중에 다시 조회해야 합니다."
                      + " ready면 정상 종료 기준으로 지금 title이 최종 값이며, 생성이 deadline을 넘겨 닫힌"
                      + " 경우에만 뒤늦은 반영으로 한 번 더 바뀔 수 있습니다.",
              allowableValues = {"generating", "ready"},
              example = "generating")
          String draftStatus) {}

  public record Signal(UUID id, String action) {}

  public static ConvertToTaskResponse from(SignalActionResult result) {
    SignalActionResult.TaskResult task = result.task();
    return new ConvertToTaskResponse(
        new Task(task.id(), task.title(), task.status(), MobileDraftStatus.key(task.draftStatus())),
        new Signal(result.signalId(), result.actionType()));
  }
}
