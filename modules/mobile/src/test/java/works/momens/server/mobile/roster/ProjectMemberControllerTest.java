package works.momens.server.mobile.roster;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.security.Principal;
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

/**
 * 컨트롤러가 경로 변수와 검색어, Principal을 조합 서비스에 그대로 전달하고 snake_case 응답 shape를 내는지 검증합니다. versioning은 모듈 경계상
 * app 설정을 못 가져오므로 슬라이스 안에서 동일하게 구성합니다. 인증 실패(401)와 에러 응답 shape는 app의 통합 테스트가 봅니다.
 */
@WebMvcTest(ProjectMemberController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(ProjectMemberControllerTest.ApiVersioningTestConfig.class)
class ProjectMemberControllerTest {

  @Autowired private MockMvc mockMvc;
  @MockitoBean private ProjectMemberService projectMemberService;

  private static final UUID USER_ID = UUID.fromString("5d2f7f3a-5db1-4f2c-8b9e-13607dd1f5e8");
  private static final UUID PROJECT_ID = UUID.fromString("30d9e9fe-f43b-4097-a88e-dc19f0a5b025");
  private final Principal principal = USER_ID::toString;

  @Test
  void listMembersReturnsSnakeCaseMembers() throws Exception {
    UUID gyuil = UUID.randomUUID();
    UUID jinsu = UUID.randomUUID();
    when(projectMemberService.list(eq(PROJECT_ID), eq(USER_ID), eq(null)))
        .thenReturn(
            List.of(
                new ProjectMember(gyuil, "김규일", "https://a/gyuil.png"),
                new ProjectMember(jinsu, "신진수", null)));

    mockMvc
        .perform(
            get("/api/mobile/projects/{projectId}/members", PROJECT_ID)
                .principal(principal)
                .header("API-Version", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.members.length()").value(2))
        .andExpect(jsonPath("$.members[0].id").value(gyuil.toString()))
        .andExpect(jsonPath("$.members[0].name").value("김규일"))
        .andExpect(jsonPath("$.members[0].avatar_url").value("https://a/gyuil.png"))
        // 신규 Standard 계약이라 avatar_url은 값이 없어도 null로 항상 포함된다(명세 예시와 동일).
        .andExpect(jsonPath("$.members[1].avatar_url", nullValue()));
  }

  @Test
  void listMembersPassesQueryThrough() throws Exception {
    when(projectMemberService.list(eq(PROJECT_ID), eq(USER_ID), eq("진수")))
        .thenReturn(List.of(new ProjectMember(UUID.randomUUID(), "신진수", null)));

    mockMvc
        .perform(
            get("/api/mobile/projects/{projectId}/members", PROJECT_ID)
                .param("query", "진수")
                .principal(principal)
                .header("API-Version", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.members[0].name").value("신진수"));
  }

  @TestConfiguration
  static class ApiVersioningTestConfig implements WebMvcConfigurer {
    @Override
    public void configureApiVersioning(ApiVersionConfigurer configurer) {
      configurer.useRequestHeader("API-Version").addSupportedVersions("1").setDefaultVersion("1");
    }
  }
}
