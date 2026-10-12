package works.momens.server.mobile.signal.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import works.momens.server.signal.SignalActionResult;

/**
 * {@code POST /api/mobile/signals/{signalId}/actions/dismiss} 응답. 형식은 docs/spec/openapi.json에서
 * 확인합니다. 새로 처리·멱등 replay 모두 200이며 body shape는 같습니다.
 */
@Schema(description = "dismiss 처리 결과")
public record DismissResponse(Signal signal) {

  public record Signal(UUID id, String action) {}

  public static DismissResponse from(SignalActionResult result) {
    return new DismissResponse(new Signal(result.signalId(), result.actionType()));
  }
}
