package works.momens.server.auth.dev;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import works.momens.server.auth.dev.dto.request.DevTokenRequest;
import works.momens.server.auth.dev.dto.response.DevTokenResponse;
import works.momens.server.auth.internal.application.DevTokenService;
import works.momens.server.common.config.DevOnly;

/**
 * dev 전용 토큰 발급 컨트롤러. {@link DevOnly} 프로필에서만 등록되므로 prod에는 존재하지 않습니다. 시크릿 헤더가 없으면 서비스가 401을 내도록
 * {@code required = false}로 받아 Spring 기본 400 대신 도메인 에러로 처리합니다.
 */
@DevOnly
@RestController
@RequestMapping("/api/dev")
@RequiredArgsConstructor
class DevTokenController implements DevTokenControllerDocs {

  private static final String SECRET_HEADER = "X-Dev-Token-Secret";

  private final DevTokenService devTokenService;

  @Override
  @PostMapping(path = "/auth/token", version = "1")
  public DevTokenResponse issueDevToken(
      @RequestHeader(name = SECRET_HEADER, required = false) String secret,
      @Valid @RequestBody(required = false) DevTokenRequest request) {
    String email = request == null ? null : request.email();
    return new DevTokenResponse(devTokenService.issueForTestUser(secret, email), "Bearer");
  }
}
