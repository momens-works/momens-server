package works.momens.server.signal.dev;

import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import works.momens.server.common.config.DevOnly;
import works.momens.server.signal.dev.dto.request.CreateDevSignalRequest;
import works.momens.server.signal.dev.dto.response.CreateDevSignalResponse;

/**
 * dev 데모용 Signal 생성 엔드포인트. {@link DevOnly} 프로필에서만 등록되므로 prod에는 존재하지 않습니다.
 *
 * <p>보호 체인의 기본 인증 대상이라 유효한 Bearer access token이 필요하지만, 데모 도구라 호출자의 workspace 멤버십이나 role은 검사하지 않습니다.
 */
@DevOnly
@RestController
@RequestMapping("/api/dev")
@RequiredArgsConstructor
class DevSignalController implements DevSignalControllerDocs {

  private final DevSignalWriter devSignalWriter;

  @Override
  @PostMapping(path = "/projects/{projectId}/signals", version = "1")
  @ResponseStatus(HttpStatus.CREATED)
  public CreateDevSignalResponse createSignal(
      @PathVariable UUID projectId, @Valid @RequestBody CreateDevSignalRequest request) {
    return new CreateDevSignalResponse(devSignalWriter.create(projectId, request));
  }
}
