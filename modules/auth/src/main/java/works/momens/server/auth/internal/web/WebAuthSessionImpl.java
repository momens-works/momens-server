package works.momens.server.auth.internal.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;
import works.momens.server.auth.WebAuthCookieUpdate;
import works.momens.server.auth.WebAuthRedirect;
import works.momens.server.auth.WebAuthSession;
import works.momens.server.auth.internal.application.WebAuthService;
import works.momens.server.auth.internal.config.AuthProperties;
import works.momens.server.auth.internal.jwt.TokenPair;
import works.momens.server.common.api.BusinessException;
import works.momens.server.common.api.ErrorCode;

/**
 * 웹 인증의 전송 계층. {@link WebAuthService}의 use case 결과를 쿠키 헤더와 리다이렉트 대상으로 완성합니다.
 *
 * <p>이 클래스가 auth에 있는 이유는 쿠키 속성·success/failure URI·실패 코드 매핑이 모두 auth의 정책이기 때문입니다. 표면 모듈은 결과를 응답에
 * 옮기기만 합니다(MOM-0852).
 */
@Slf4j
@Component
@RequiredArgsConstructor
class WebAuthSessionImpl implements WebAuthSession {

  private final WebAuthService webAuthService;
  private final WebAuthCookies cookies;
  private final AuthProperties properties;

  @Override
  public WebAuthRedirect startLogin() {
    try {
      WebAuthService.LoginRedirect redirect = webAuthService.startLogin();
      return new WebAuthRedirect(
          List.of(
              cookies.state(redirect.state()).toString(),
              cookies.pkceVerifier(redirect.codeVerifier()).toString()),
          redirect.authorizationUrl());
    } catch (RuntimeException e) {
      // 콜백과 동일하게 브라우저에는 기본 에러 대신 failure-uri로 보냅니다.
      log.error("web oauth login start failed", e);
      return new WebAuthRedirect(List.of(), failureUri("server_error"));
    }
  }

  @Override
  public WebAuthRedirect completeLogin(HttpServletRequest request, String code, String state) {
    // 핸드셰이크 쿠키는 결과와 무관하게 정리합니다.
    List<String> setCookies =
        new ArrayList<>(
            List.of(cookies.clearState().toString(), cookies.clearPkceVerifier().toString()));
    try {
      TokenPair tokens =
          webAuthService.completeLogin(
              code,
              state,
              cookies.readState(request).orElse(null),
              cookies.readPkceVerifier(request).orElse(null));
      setCookies.add(cookies.accessToken(tokens.accessToken()).toString());
      setCookies.add(cookies.refreshToken(tokens.refreshToken()).toString());
      return new WebAuthRedirect(setCookies, properties.web().redirect().successUri());
    } catch (BusinessException e) {
      log.warn("web oauth callback failed: code={}", e.getErrorCode().code());
      return new WebAuthRedirect(setCookies, failureUri(failureError(e.getErrorCode())));
    } catch (RuntimeException e) {
      // 외부 호출·발급 중 예기치 못한 예외도 브라우저에 JSON을 노출하지 않고 failure-uri로 보냅니다.
      log.error("web oauth callback failed unexpectedly", e);
      return new WebAuthRedirect(setCookies, failureUri("server_error"));
    }
  }

  @Override
  public WebAuthCookieUpdate refresh(HttpServletRequest request) {
    TokenPair tokens = webAuthService.refresh(cookies.readRefreshToken(request).orElse(null));
    return new WebAuthCookieUpdate(
        List.of(
            cookies.accessToken(tokens.accessToken()).toString(),
            cookies.refreshToken(tokens.refreshToken()).toString()));
  }

  @Override
  public WebAuthCookieUpdate logout(HttpServletRequest request) {
    cookies
        .readRefreshToken(request)
        .ifPresent(
            refreshToken -> {
              try {
                webAuthService.logout(refreshToken);
              } catch (BusinessException e) {
                // 이미 무효·폐기된 refresh라도 로그아웃은 쿠키를 정리하고 성공으로 끝냅니다(멱등).
                log.debug("web logout with inactive refresh: code={}", e.getErrorCode().code());
              }
            });
    return new WebAuthCookieUpdate(
        List.of(
            cookies.clearAccessToken().toString(),
            cookies.clearRefreshToken().toString(),
            cookies.clearLegacySessionToken().toString()));
  }

  private String failureUri(String error) {
    return UriComponentsBuilder.fromUriString(properties.web().redirect().failureUri())
        .queryParam("error", error)
        .build()
        .encode()
        .toUriString();
  }

  private static String failureError(ErrorCode errorCode) {
    return switch (errorCode.code()) {
      case "AUTH_OAUTH_STATE_INVALID" -> "invalid_state";
      case "AUTH_GOOGLE_EMAIL_NOT_VERIFIED" -> "email_not_verified";
      case "AUTH_OAUTH_EXCHANGE_FAILED" -> "google_error";
      case "USER_EMAIL_LINKED_TO_ANOTHER_IDENTITY" -> "email_conflict";
      default -> "server_error";
    };
  }
}
