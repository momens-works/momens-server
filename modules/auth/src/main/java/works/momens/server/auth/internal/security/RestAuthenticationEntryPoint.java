package works.momens.server.auth.internal.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import works.momens.server.common.api.CommonErrorCode;

/**
 * 401 본문을 Standard 에러 shape로 내보냅니다.
 *
 * <p>{@code docs/rules/code-conventions.md}의 「401 · 403」 규칙에 따라 인증 정보 없음은 {@code
 * AUTH_UNAUTHORIZED}, 토큰 파싱/검증 실패(만료·서명 불일치·형식 오류)는 {@code AUTH_INVALID_TOKEN}으로 분리합니다.
 */
@Component
@RequiredArgsConstructor
class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

  private final SecurityErrorWriter errorWriter;

  @Override
  public void commence(
      HttpServletRequest request,
      HttpServletResponse response,
      AuthenticationException authenticationException)
      throws IOException {
    CommonErrorCode errorCode =
        (authenticationException instanceof InvalidBearerTokenException)
            ? CommonErrorCode.AUTH_INVALID_TOKEN
            : CommonErrorCode.AUTH_UNAUTHORIZED;
    errorWriter.write(response, errorCode);
  }
}
