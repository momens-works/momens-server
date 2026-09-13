package works.momens.server.web.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.servlet.config.annotation.ApiVersionConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import works.momens.server.auth.WebAuthCookieUpdate;
import works.momens.server.auth.WebAuthRedirect;
import works.momens.server.auth.WebAuthSession;

/**
 * 웹 인증 HTTP 표면 검증.
 *
 * <p>쿠키 속성·리다이렉트 대상·실패 코드 매핑은 auth가 소유하므로({@code WebAuthSessionImplTest}) 여기서는 받은 결과를 응답 헤더와 상태 코드로
 * 옮기는 부분만 확인합니다.
 */
@WebMvcTest(WebAuthController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(WebAuthControllerTest.ApiVersionTestConfig.class)
class WebAuthControllerTest {

  private static final String API_VERSION_HEADER = "API-Version";
  private static final String API_VERSION = "1";
  private static final String SUCCESS_URI = "http://localhost:3000/auth/success";

  @Autowired private MockMvc mockMvc;
  @MockitoBean private WebAuthSession webAuthSession;

  @Test
  @DisplayName("로그인 시작은 받은 쿠키 헤더를 붙이고 consent URL로 리다이렉트한다")
  void googleLoginAppliesCookiesAndRedirects() throws Exception {
    when(webAuthSession.startLogin())
        .thenReturn(
            new WebAuthRedirect(
                List.of("oauth_state=state-xyz; Path=/api/auth/google/callback; HttpOnly"),
                "https://accounts.google.com/o/oauth2/v2/auth?state=state-xyz"));

    MvcResult result =
        mockMvc
            .perform(get("/api/auth/google/login").header(API_VERSION_HEADER, API_VERSION))
            .andExpect(status().isFound())
            .andExpect(
                redirectedUrl("https://accounts.google.com/o/oauth2/v2/auth?state=state-xyz"))
            .andReturn();

    assertThat(result.getResponse().getHeaders("Set-Cookie"))
        .containsExactly("oauth_state=state-xyz; Path=/api/auth/google/callback; HttpOnly");
  }

  @Test
  @DisplayName("콜백은 요청을 auth로 넘기고 받은 결과를 응답에 그대로 옮긴다")
  void googleCallbackPassesRequestCookiesToAuthAndAppliesResult() throws Exception {
    when(webAuthSession.completeLogin(any(), eq("auth-code"), eq("state-xyz")))
        .thenReturn(
            new WebAuthRedirect(List.of("access_token=access-jwt; Path=/; HttpOnly"), SUCCESS_URI));

    MvcResult result =
        mockMvc
            .perform(
                get("/api/auth/google/callback")
                    .header(API_VERSION_HEADER, API_VERSION)
                    .param("code", "auth-code")
                    .param("state", "state-xyz")
                    .cookie(new Cookie("oauth_state", "state-xyz")))
            .andExpect(status().isFound())
            .andExpect(redirectedUrl(SUCCESS_URI))
            .andReturn();

    assertThat(result.getResponse().getHeaders("Set-Cookie"))
        .containsExactly("access_token=access-jwt; Path=/; HttpOnly");
  }

  @Test
  @DisplayName("세션 갱신은 받은 쿠키 헤더를 붙이고 204로 응답한다")
  void webRefreshAppliesCookiesAndReturns204() throws Exception {
    when(webAuthSession.refresh(any()))
        .thenReturn(
            new WebAuthCookieUpdate(
                List.of(
                    "access_token=new-access-jwt; Path=/; HttpOnly",
                    "refresh_token=new-refresh-token; Path=/api/auth; HttpOnly")));

    MvcResult result =
        mockMvc
            .perform(
                post("/api/auth/web/refresh")
                    .header(API_VERSION_HEADER, API_VERSION)
                    .cookie(new Cookie("refresh_token", "old-web-refresh")))
            .andExpect(status().isNoContent())
            .andReturn();

    assertThat(result.getResponse().getHeaders("Set-Cookie"))
        .containsExactly(
            "access_token=new-access-jwt; Path=/; HttpOnly",
            "refresh_token=new-refresh-token; Path=/api/auth; HttpOnly");
  }

  @Test
  @DisplayName("로그아웃은 세 쿠키 만료 헤더를 붙이고 204로 응답한다")
  void webLogoutAppliesCookiesAndReturns204() throws Exception {
    when(webAuthSession.logout(any()))
        .thenReturn(
            new WebAuthCookieUpdate(
                List.of(
                    "access_token=; Path=/; Max-Age=0; HttpOnly",
                    "refresh_token=; Path=/api/auth; Max-Age=0; HttpOnly",
                    "session_token=; Path=/; Max-Age=0; HttpOnly")));

    MvcResult result =
        mockMvc
            .perform(post("/api/auth/web/logout").header(API_VERSION_HEADER, API_VERSION))
            .andExpect(status().isNoContent())
            .andReturn();

    verify(webAuthSession).logout(any());
    assertThat(result.getResponse().getHeaders("Set-Cookie"))
        .anyMatch(
            c -> c.startsWith("access_token=") && c.contains("Path=/;") && c.contains("Max-Age=0"))
        .anyMatch(
            c ->
                c.startsWith("refresh_token=")
                    && c.contains("Path=/api/auth;")
                    && c.contains("Max-Age=0"))
        .anyMatch(
            c -> c.startsWith("session_token=") && c.contains("Path=/;") && c.contains("Max-Age=0"))
        .hasSize(3);
  }

  @TestConfiguration
  static class ApiVersionTestConfig implements WebMvcConfigurer {

    @Override
    public void configureApiVersioning(ApiVersionConfigurer configurer) {
      configurer
          .useRequestHeader(API_VERSION_HEADER)
          .addSupportedVersions(API_VERSION)
          .setVersionRequired(false);
    }
  }
}
