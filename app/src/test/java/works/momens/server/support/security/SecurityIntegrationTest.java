package works.momens.server.support.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import works.momens.server.auth.AccessTokenTestFactory;
import works.momens.server.common.test.AbstractPostgresIntegrationTest;
import works.momens.server.user.UserProfile;
import works.momens.server.user.UserService;

/**
 * SecurityFilterChain(MOM-8) 실배선 통합테스트.
 *
 * <p>auth가 발급한 HS256 access로 보호 엔드포인트(`/api/me`)가 실제로 인증되고 `Principal.name=userId` seam이 동작하는지,
 * 미인증/위조/만료 요청이 규격대로 401(인증정보 없음=AUTH_UNAUTHORIZED, 토큰 검증 실패=AUTH_INVALID_TOKEN)을 내는지 확인합니다.
 *
 * <p>토큰은 auth의 public testFixtures({@link AccessTokenTestFactory})로 만들어 모듈 경계를 지킵니다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SecurityIntegrationTest extends AbstractPostgresIntegrationTest {

  private static final String API_VERSION_HEADER = "API-Version";
  private static final String API_VERSION = "1";

  @Autowired private MockMvc mockMvc;
  @Autowired private AccessTokenTestFactory accessTokens;
  @Autowired private UserService userService;

  @Test
  void returnsProfileWhenAccessTokenValid() throws Exception {
    UserProfile user = userService.findOrCreate("auth-it@momens.works", "홍길동", null);
    String token = accessTokens.issueAccessToken(user.id());

    mockMvc
        .perform(get("/api/me").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.user.id").value(user.id().toString()));
  }

  @Test
  void returnsProfileWhenAccessTokenInCookie() throws Exception {
    UserProfile user = userService.findOrCreate("auth-it-web@momens.works", "홍길동", null);
    String token = accessTokens.issueAccessToken(user.id());

    mockMvc
        .perform(get("/api/me").cookie(new Cookie("access_token", token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.user.id").value(user.id().toString()));
  }

  @Test
  void returnsStandardUnauthorizedWhenNoToken() throws Exception {
    mockMvc
        .perform(get("/api/me"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error.code").value("AUTH_UNAUTHORIZED"));
  }

  @Test
  void returnsInvalidTokenWhenMalformed() throws Exception {
    mockMvc
        .perform(get("/api/me").header("Authorization", "Bearer not-a-jwt"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error.code").value("AUTH_INVALID_TOKEN"));
  }

  @Test
  void returnsInvalidTokenWhenSignatureForged() throws Exception {
    String forged = tamperSignature(accessTokens.issueAccessToken(UUID.randomUUID()));

    mockMvc
        .perform(get("/api/me").header("Authorization", "Bearer " + forged))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error.code").value("AUTH_INVALID_TOKEN"));
  }

  @Test
  void returnsInvalidTokenWhenExpired() throws Exception {
    String expired = accessTokens.issueExpiredAccessToken(UUID.randomUUID());

    mockMvc
        .perform(get("/api/me").header("Authorization", "Bearer " + expired))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error.code").value("AUTH_INVALID_TOKEN"));
  }

  @Test
  void appUpdateIsPermitAllWithoutToken() throws Exception {
    mockMvc
        .perform(
            get("/api/mobile/app-update")
                .header(API_VERSION_HEADER, API_VERSION)
                .param("platform", "android")
                .param("app_version", "1.0.0"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.update_required").value(false));
  }

  @Test
  void authEndpointsArePermitAllAndHandledOutsideResourceServer() throws Exception {
    mockMvc
        .perform(
            post("/api/auth/refresh")
                .header(API_VERSION_HEADER, API_VERSION)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refresh_token\":\"unknown-refresh-token\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error.code").value("AUTH_REFRESH_TOKEN_INVALID"));
  }

  @Test
  void webRefreshIsPermitAllAndReturnsStandardErrorWhenRefreshCookieInvalid() throws Exception {
    mockMvc
        .perform(
            post("/api/auth/web/refresh")
                .header(API_VERSION_HEADER, API_VERSION)
                .cookie(new Cookie("refresh_token", "unknown-refresh-token")))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error.code").value("AUTH_REFRESH_TOKEN_INVALID"));
  }

  @Test
  void webRefreshReturnsStandardErrorWhenNoRefreshCookie() throws Exception {
    mockMvc
        .perform(post("/api/auth/web/refresh").header(API_VERSION_HEADER, API_VERSION))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error.code").value("AUTH_REFRESH_TOKEN_INVALID"));
  }

  @Test
  void webLogoutIsPermitAllAndReturns204WhenNoRefreshCookie() throws Exception {
    mockMvc
        .perform(post("/api/auth/web/logout").header(API_VERSION_HEADER, API_VERSION))
        .andExpect(status().isNoContent());
  }

  @Test
  void webEndpointsIgnoreStaleBearerHeaderAndReachController() throws Exception {
    // 만료된 Authorization 헤더가 붙어도 웹 쿠키 경로가 자원서버보다 먼저 매칭돼 컨트롤러까지 도달해야 합니다.
    String expired = accessTokens.issueExpiredAccessToken(UUID.randomUUID());

    mockMvc
        .perform(
            post("/api/auth/web/refresh")
                .header("Authorization", "Bearer " + expired)
                .header(API_VERSION_HEADER, API_VERSION)
                .cookie(new Cookie("refresh_token", "unknown-refresh-token")))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error.code").value("AUTH_REFRESH_TOKEN_INVALID"));

    mockMvc
        .perform(
            post("/api/auth/web/logout")
                .header("Authorization", "Bearer " + expired)
                .header(API_VERSION_HEADER, API_VERSION))
        .andExpect(status().isNoContent());
  }

  @Test
  void authEndpointsIgnoreStaleBearerHeaderAndReachController() throws Exception {
    String expired = accessTokens.issueExpiredAccessToken(UUID.randomUUID());

    mockMvc
        .perform(
            post("/api/auth/refresh")
                .header("Authorization", "Bearer " + expired)
                .header(API_VERSION_HEADER, API_VERSION)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refresh_token\":\"unknown-refresh-token\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error.code").value("AUTH_REFRESH_TOKEN_INVALID"));
  }

  @Test
  void preflightFromAllowedOriginEchoesCorsHeaders() throws Exception {
    // 허용된 웹 origin의 preflight(OPTIONS)는 자원서버보다 먼저 CORS 필터가 처리해 헤더를 echo 합니다.
    mockMvc
        .perform(
            options("/api/me")
                .header("Origin", "http://localhost:3000")
                .header("Access-Control-Request-Method", "GET"))
        .andExpect(status().isOk())
        .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"))
        .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
  }

  @Test
  void preflightFromDisallowedOriginIsRejected() throws Exception {
    mockMvc
        .perform(
            options("/api/me")
                .header("Origin", "https://evil.example")
                .header("Access-Control-Request-Method", "GET"))
        .andExpect(status().isForbidden())
        .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
  }

  @Test
  void preflightOnPublicChainEchoesCorsHeaders() throws Exception {
    // 공개 체인(/api/auth/web/*)에도 CORS를 얹었으므로, 인증 전에 preflight(OPTIONS)가 처리돼 헤더를 echo 합니다.
    mockMvc
        .perform(
            options("/api/auth/web/refresh")
                .header("Origin", "http://localhost:3000")
                .header("Access-Control-Request-Method", "POST"))
        .andExpect(status().isOk())
        .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"))
        .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
  }

  @Test
  void actualRequestFromAllowedOriginKeepsAuthAndAddsCorsHeader() throws Exception {
    UserProfile user = userService.findOrCreate("auth-it-cors@momens.works", "홍길동", null);
    String token = accessTokens.issueAccessToken(user.id());

    mockMvc
        .perform(
            get("/api/me")
                .header("Origin", "http://localhost:3000")
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"))
        .andExpect(jsonPath("$.user.id").value(user.id().toString()));
  }

  /**
   * 서명 세그먼트의 <b>첫</b> 문자를 바꿔 구조는 유효하나 서명이 어긋난 토큰을 만듭니다.
   *
   * <p>마지막 문자는 32바이트 HS256 서명에서 유효 비트가 4개뿐(2비트 미사용)이라 같은 상위 4비트 문자로 바꾸면 디코딩된 서명이 그대로일 수 있습니다. 6비트가
   * 모두 유효한 첫 문자를 바꿔 변조가 항상 반영되게 합니다.
   */
  private static String tamperSignature(String jwt) {
    int signatureStart = jwt.lastIndexOf('.') + 1;
    char first = jwt.charAt(signatureStart);
    char replacement = (first == 'A') ? 'B' : 'A';
    return jwt.substring(0, signatureStart) + replacement + jwt.substring(signatureStart + 1);
  }
}
