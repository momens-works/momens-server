package works.momens.server.mobile.signal.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import works.momens.server.signal.SignalActionResult;

@Schema(description = "dismiss 처리 결과")
public record DismissResponse(Signal signal) {

  @Schema(name = "DismissSignal")
  public record Signal(
      UUID id, @Schema(description = "처리한 action", example = "dismiss") String action) {}

  public static DismissResponse from(SignalActionResult result) {
    return new DismissResponse(new Signal(result.signalId(), result.actionType()));
  }
}
