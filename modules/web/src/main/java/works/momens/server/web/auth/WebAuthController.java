package works.momens.server.web.auth;

import io.swagger.v3.oas.annotations.Parameter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import works.momens.server.auth.WebAuthCookieUpdate;
import works.momens.server.auth.WebAuthRedirect;
import works.momens.server.auth.WebAuthSession;

/**
 * 웹 Google 로그인(서버 주도 Authorization Code) 엔드포인트.
 *
 * <p>쿠키 속성·리다이렉트 대상·실패 코드 매핑은 auth가 소유하고({@link WebAuthSession}), 이 컨트롤러는 받은 결과를 응답으로 옮기기만 합니다.
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
class WebAuthController implements WebAuthControllerDocs {

  private final WebAuthSession webAuthSession;

  @Override
  @GetMapping(path = "/google/login", version = "1")
  public void googleLogin(@Parameter(hidden = true) HttpServletResponse response)
      throws IOException {
    redirect(webAuthSession.startLogin(), response);
  }

  @Override
  @GetMapping(path = "/google/callback", version = "1")
  public void googleCallback(
      @RequestParam(name = "code", required = false) String code,
      @RequestParam(name = "state", required = false) String state,
      @Parameter(hidden = true) HttpServletRequest request,
      @Parameter(hidden = true) HttpServletResponse response)
      throws IOException {
    redirect(webAuthSession.completeLogin(request, code, state), response);
  }

  @Override
  @PostMapping(path = "/web/refresh", version = "1")
  public ResponseEntity<Void> webRefresh(@Parameter(hidden = true) HttpServletRequest request) {
    return noContent(webAuthSession.refresh(request));
  }

  @Override
  @PostMapping(path = "/web/logout", version = "1")
  public ResponseEntity<Void> webLogout(@Parameter(hidden = true) HttpServletRequest request) {
    return noContent(webAuthSession.logout(request));
  }

  private static void redirect(WebAuthRedirect result, HttpServletResponse response)
      throws IOException {
    result.setCookieHeaders().forEach(cookie -> response.addHeader(HttpHeaders.SET_COOKIE, cookie));
    response.sendRedirect(result.redirectUri());
  }

  private static ResponseEntity<Void> noContent(WebAuthCookieUpdate result) {
    HttpHeaders headers = new HttpHeaders();
    result.setCookieHeaders().forEach(cookie -> headers.add(HttpHeaders.SET_COOKIE, cookie));
    return ResponseEntity.noContent().headers(headers).build();
  }
}
