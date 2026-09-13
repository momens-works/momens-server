package works.momens.server.web.auth;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.ResponseEntity;
import works.momens.server.auth.AuthErrorCode;
import works.momens.server.common.api.ApiException;
import works.momens.server.common.api.CommonErrorCode;

/**
 * 웹 인증(Authorization Code 로그인 + 쿠키 세션) OpenAPI 문서.
 *
 * <p>로그인({@code google/login}·{@code google/callback})은 브라우저 리다이렉트(302)라 JSON 본문이 없고 실패도
 * failure-uri로 리다이렉트합니다. 세션 갱신·로그아웃({@code web/refresh}·{@code web/logout})은 FE의 fetch 호출이라 토큰을
 * HttpOnly 쿠키로 주고받으며 본문 없이 204를 반환합니다(refresh 무효 시 표준 JSON 에러).
 *
 * <p>네 엔드포인트 모두 공개이므로(SecurityConfig의 공개 체인) OpenApiConfig가 적용한 전역 Bearer 요구에서
 * {@code @SecurityRequirements}(빈 값)로 제외합니다.
 */
@Tag(name = "Auth", description = "인증 API")
interface WebAuthControllerDocs {

  @Operation(
      operationId = "startGoogleLogin",
      summary = "웹 Google 로그인 시작",
      description = "state·PKCE 쿠키를 설정하고 Google consent 화면으로 리다이렉트합니다.")
  @ApiResponse(responseCode = "302", description = "Google consent로 리다이렉트")
  @SecurityRequirements
  void googleLogin(HttpServletResponse response) throws IOException;

  @Operation(
      operationId = "completeGoogleLogin",
      summary = "웹 Google 로그인 콜백",
      description =
          "code를 교환해 WEB 세션 토큰을 발급합니다. 성공: access/refresh HttpOnly 쿠키 설정 후 success-uri로"
              + " 리다이렉트. 실패: failure-uri로 리다이렉트하며 `?error=`에는 `invalid_state`,"
              + " `email_not_verified`, `email_conflict`, `google_error`, `server_error` 중 하나를"
              + " 전달합니다.")
  @ApiResponse(responseCode = "302", description = "성공/실패 모두 리다이렉트")
  @SecurityRequirements
  void googleCallback(
      String code,
      String state,
      @Parameter(hidden = true) HttpServletRequest request,
      HttpServletResponse response)
      throws IOException;

  @Operation(
      operationId = "webRefreshSession",
      summary = "웹 세션 갱신",
      description = "refresh 쿠키를 회전해 새 access/refresh HttpOnly 쿠키를 설정합니다. 본문은 없습니다.")
  @ApiResponse(responseCode = "204", description = "갱신 성공(Set-Cookie로 access/refresh 회전)")
  @SecurityRequirements
  @ApiException(
      value = AuthErrorCode.class,
      codes = {"AUTH_REFRESH_TOKEN_INVALID"})
  @ApiException(CommonErrorCode.class)
  ResponseEntity<Void> webRefresh(@Parameter(hidden = true) HttpServletRequest request);

  @Operation(
      operationId = "webLogout",
      summary = "웹 로그아웃",
      description =
          "refresh 쿠키를 폐기하고 access/refresh/legacy session 쿠키를 정리합니다. 쿠키 유무·상태와 무관하게 204를 반환합니다(멱등).")
  @ApiResponse(responseCode = "204", description = "로그아웃 성공(access/refresh/legacy session 쿠키 정리)")
  @SecurityRequirements
  ResponseEntity<Void> webLogout(@Parameter(hidden = true) HttpServletRequest request);
}
