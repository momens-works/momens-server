package works.momens.server.mobile.bootstrap;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.security.Principal;
import java.time.Instant;
import java.util.List;
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
import works.momens.server.mobile.bootstrap.BootstrapContext.AccessibleProject;
import works.momens.server.user.UserProfile;

/**
 * 컨트롤러가 Principal로 현재 사용자를 해석하고 명세(docs/spec/mobile-api.md)의 snake_case 응답 shape를 내는지 검증합니다.
 * versioning은 모듈 경계상 app 설정을 못 가져오므로 슬라이스 안에서 동일하게 구성합니다. 인증 실패(401)와 끝까지 이어지는 흐름은 app의 통합 테스트가
 * 봅니다.
 */
@WebMvcTest(BootstrapController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(BootstrapControllerTest.ApiVersioningTestConfig.class)
class BootstrapControllerTest {

  @Autowired private MockMvc mockMvc;
  @MockitoBean private BootstrapService bootstrapService;

  private static final UUID USER_ID = UUID.fromString("5d2f7f3a-5db1-4f2c-8b9e-13607dd1f5e8");
  private final Principal principal = USER_ID::toString;

  @Test
  void getBootstrapReturnsSnakeCaseEntryContext() throws Exception {
    UUID projectId = UUID.fromString("30d9e9fe-f43b-4097-a88e-dc19f0a5b025");
    when(bootstrapService.load(eq(USER_ID)))
        .thenReturn(
            new BootstrapContext(
                profile(),
                projectId,
                List.of(new AccessibleProject(projectId, "Q2 Activation Readiness", "member")),
                false));

    mockMvc
        .perform(get("/api/mobile/bootstrap").principal(principal).header("API-Version", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.me.id").value(USER_ID.toString()))
        .andExpect(jsonPath("$.me.email").value("minji@momens.works"))
        .andExpect(jsonPath("$.me.name").value("김민지"))
        // 신규 Standard 계약이라 avatar_url은 값이 없어도 null로 항상 포함된다(명세 예시와 동일).
        .andExpect(jsonPath("$.me.avatar_url", nullValue()))
        .andExpect(jsonPath("$.default_project_id").value(projectId.toString()))
        .andExpect(jsonPath("$.projects[0].id").value(projectId.toString()))
        .andExpect(jsonPath("$.projects[0].name").value("Q2 Activation Readiness"))
        .andExpect(jsonPath("$.projects[0].role").value("member"));
  }

  @Test
  void getBootstrapReturnsNullDefaultAndEmptyProjectsWhenUserHasNoProject() throws Exception {
    when(bootstrapService.load(eq(USER_ID)))
        .thenReturn(new BootstrapContext(profile(), null, List.of(), false));

    mockMvc
        .perform(get("/api/mobile/bootstrap").principal(principal).header("API-Version", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.default_project_id", nullValue()))
        .andExpect(jsonPath("$.projects").isEmpty());
  }

  private static UserProfile profile() {
    return new UserProfile(
        USER_ID, "minji@momens.works", "김민지", null, null, Instant.now(), Instant.now());
  }

  @TestConfiguration
  static class ApiVersioningTestConfig implements WebMvcConfigurer {
    @Override
    public void configureApiVersioning(ApiVersionConfigurer configurer) {
      configurer.useRequestHeader("API-Version").addSupportedVersions("1").setDefaultVersion("1");
    }
  }
}
