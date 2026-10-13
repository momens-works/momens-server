package works.momens.server.web.user;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.security.Principal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.config.annotation.ApiVersionConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import works.momens.server.user.UserProfile;
import works.momens.server.user.UserService;

/**
 * 컨트롤러가 Principal로 현재 사용자를 해석하고 레거시 호환 응답 shape를 내는지 검증합니다. path는 {@code /api/me} 단일 경로이며 {@code
 * API-Version: 1} 헤더로 분기합니다(레거시 alias 없음). versioning은 모듈 경계상 app 설정을 못 가져오므로 슬라이스 안에서 동일하게 구성합니다.
 */
@WebMvcTest(UserController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(UserControllerTest.ApiVersioningTestConfig.class)
class UserControllerTest {

  @Autowired private MockMvc mockMvc;
  @MockitoBean private UserService userService;

  private static final UUID USER_ID = UUID.fromString("5d2f7f3a-5db1-4f2c-8b9e-13607dd1f5e8");
  private final Principal principal = USER_ID::toString;

  @Test
  @DisplayName("내 정보 조회는 user 래퍼와 snake_case 필드로 응답한다")
  void getMeReturnsWrappedSnakeCaseProfile() throws Exception {
    when(userService.getProfile(eq(USER_ID)))
        .thenReturn(
            new UserProfile(
                USER_ID,
                "user@example.com",
                "홍길동",
                "Engineer",
                null,
                Instant.parse("2026-06-24T00:00:00Z"),
                Instant.parse("2026-06-24T00:00:00Z")));

    mockMvc
        .perform(get("/api/me").principal(principal).header("API-Version", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.user.id").value(USER_ID.toString()))
        .andExpect(jsonPath("$.user.email").value("user@example.com"))
        .andExpect(jsonPath("$.user.job_role").value("Engineer"))
        .andExpect(jsonPath("$.user.created_at").exists())
        .andExpect(jsonPath("$.user.avatar_url").doesNotExist());
  }

  @Test
  @DisplayName("프로필 수정은 변경된 프로필을 응답한다")
  void patchMeUpdatesProfile() throws Exception {
    when(userService.updateProfile(eq(USER_ID), eq("새이름"), eq("Designer")))
        .thenReturn(
            new UserProfile(
                USER_ID,
                "user@example.com",
                "새이름",
                "Designer",
                null,
                Instant.now(),
                Instant.now()));

    mockMvc
        .perform(
            patch("/api/me")
                .principal(principal)
                .header("API-Version", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"새이름\",\"job_role\":\"Designer\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.user.name").value("새이름"))
        .andExpect(jsonPath("$.user.job_role").value("Designer"));
  }

  @Test
  @DisplayName("API-Version 헤더가 없으면 v1으로 라우팅한다")
  void getMeWithoutVersionHeaderRoutesToV1() throws Exception {
    when(userService.getProfile(eq(USER_ID)))
        .thenReturn(
            new UserProfile(
                USER_ID,
                "user@example.com",
                "홍길동",
                "Engineer",
                null,
                Instant.parse("2026-06-24T00:00:00Z"),
                Instant.parse("2026-06-24T00:00:00Z")));

    // API-Version 헤더가 없어도 setDefaultVersion("1") 덕에 v1으로 라우팅된다(전이 구간 계약).
    mockMvc
        .perform(get("/api/me").principal(principal))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.user.id").value(USER_ID.toString()));
  }

  @TestConfiguration
  static class ApiVersioningTestConfig implements WebMvcConfigurer {
    @Override
    public void configureApiVersioning(ApiVersionConfigurer configurer) {
      configurer.useRequestHeader("API-Version").addSupportedVersions("1").setDefaultVersion("1");
    }
  }
}
