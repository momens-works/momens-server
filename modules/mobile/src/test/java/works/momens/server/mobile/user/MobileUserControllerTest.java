package works.momens.server.mobile.user;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.security.Principal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.config.annotation.ApiVersionConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import works.momens.server.user.UserProfile;
import works.momens.server.user.UserService;

/**
 * 컨트롤러가 {@code Principal}에서 현재 사용자를 식별하고, {@code docs/spec/mobile-api.md}에 정의된 {@code snake_case}
 * 형식으로 응답하는지 검증합니다.
 *
 * <p>모듈 경계로 인해 app의 API 버전 설정을 가져올 수 없어 테스트에서 동일한 설정을 구성합니다. 인증 실패(401)와 전체 요청 흐름은 app 모듈의 통합 테스트에서
 * 검증합니다.
 */
@WebMvcTest(MobileUserController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(MobileUserControllerTest.ApiVersioningTestConfig.class)
class MobileUserControllerTest {

  @Autowired private MockMvc mockMvc;
  @MockitoBean private UserService userService;

  private static final UUID USER_ID = UUID.fromString("5d2f7f3a-5db1-4f2c-8b9e-13607dd1f5e8");
  private final Principal principal = USER_ID::toString;

  @Test
  void getMeReturnsSnakeCaseAccountInfo() throws Exception {
    when(userService.getProfile(eq(USER_ID)))
        .thenReturn(profile("https://cdn.momens.works/avatars/minji.png"));

    mockMvc
        .perform(get("/api/mobile/me").principal(principal).header("API-Version", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(USER_ID.toString()))
        .andExpect(jsonPath("$.email").value("minji@momens.works"))
        .andExpect(jsonPath("$.name").value("김민지"))
        .andExpect(jsonPath("$.avatar_url").value("https://cdn.momens.works/avatars/minji.png"));
  }

  @Test
  void getMeReturnsNullAvatarUrlWhenProfileImageIsNotSet() throws Exception {
    when(userService.getProfile(eq(USER_ID))).thenReturn(profile(null));

    mockMvc
        .perform(get("/api/mobile/me").principal(principal).header("API-Version", "1"))
        .andExpect(status().isOk())
        // 프로필 사진이 없어도 필드를 생략하지 않고 `null`로 반환합니다. 웹 `GET /api/me`에서 해당 필드를
        // 생략하는 것과 의도적으로 구분한 동작입니다.
        .andExpect(jsonPath("$.avatar_url", nullValue()));
  }

  private static UserProfile profile(String avatarUrl) {
    return new UserProfile(
        USER_ID, "minji@momens.works", "김민지", null, avatarUrl, Instant.now(), Instant.now());
  }

  @TestConfiguration
  static class ApiVersioningTestConfig implements WebMvcConfigurer {
    @Override
    public void configureApiVersioning(ApiVersionConfigurer configurer) {
      configurer.useRequestHeader("API-Version").addSupportedVersions("1").setDefaultVersion("1");
    }
  }
}
