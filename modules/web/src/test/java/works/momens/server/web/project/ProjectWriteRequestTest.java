package works.momens.server.web.project;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.security.Principal;
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

@WebMvcTest({ProjectController.class, MilestoneController.class})
@AutoConfigureMockMvc(addFilters = false)
@Import(ProjectWriteRequestTest.ApiVersioningTestConfig.class)
class ProjectWriteRequestTest {
  private static final UUID USER_ID = UUID.randomUUID();
  private final Principal principal = USER_ID::toString;

  @Autowired private MockMvc mockMvc;
  @MockitoBean private ProjectService projectService;
  @MockitoBean private MilestoneService milestoneService;

  @Test
  @DisplayName("프로젝트와 마일스톤 생성에서 health_status가 빈 문자열이면 service를 호출하지 않고 400을 응답한다")
  void rejectsEmptyHealthStatus() throws Exception {
    String body = "{\"name\":\"이름\",\"health_status\":\"\"}";
    mockMvc
        .perform(
            post("/api/workspaces/{workspaceId}/projects", UUID.randomUUID())
                .principal(principal)
                .header("API-Version", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(
            post("/api/projects/{projectId}/milestones", UUID.randomUUID())
                .principal(principal)
                .header("API-Version", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(projectService, milestoneService);
  }

  @TestConfiguration
  static class ApiVersioningTestConfig implements WebMvcConfigurer {
    @Override
    public void configureApiVersioning(ApiVersionConfigurer configurer) {
      configurer.useRequestHeader("API-Version").addSupportedVersions("1").setDefaultVersion("1");
    }
  }
}
