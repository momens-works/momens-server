package works.momens.server.signal.dev;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.config.annotation.ApiVersionConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 컨트롤러가 요청 검증(5.3절 입력 계약)을 400으로 거부하고 201과 생성된 id를 반환하는지 검증합니다. {@code @DevOnly} 컨트롤러라 test 프로필을
 * 활성화합니다. versioning은 모듈 경계상 app 설정을 못 가져오므로 슬라이스 안에서 동일하게 구성합니다.
 */
@WebMvcTest(DevSignalController.class)
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@Import(DevSignalControllerTest.ApiVersioningTestConfig.class)
class DevSignalControllerTest {

  @Autowired private MockMvc mockMvc;
  @MockitoBean private DevSignalWriter devSignalWriter;

  private static final UUID PROJECT_ID = UUID.fromString("30d9e9fe-f43b-4097-a88e-dc19f0a5b025");

  @Test
  @DisplayName("Signal 생성은 201과 생성된 id를 반환한다")
  void createReturnsCreatedWithId() throws Exception {
    UUID signalId = UUID.randomUUID();
    when(devSignalWriter.create(eq(PROJECT_ID), any())).thenReturn(signalId);

    mockMvc
        .perform(
            post("/api/dev/projects/{projectId}/signals", PROJECT_ID)
                .header("API-Version", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "type": "risk",
                      "title": "결제 정책 결정 3일째 보류",
                      "description": "결제 정책 결정이 3일 동안 보류된 상태입니다.",
                      "occurred_at": "2026-07-16T10:30:00+09:00",
                      "evidence": [
                        {
                          "source_type": "slack",
                          "source_title": "결제 정책 논의",
                          "details": {"target": "결제 정책", "change": "결정 3일째 보류", "impact": "일정 지연 가능"}
                        }
                      ]
                    }
                    """))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(signalId.toString()));
  }

  @Test
  @DisplayName("허용하지 않은 evidence source_type은 400으로 거부한다")
  void rejectsUnknownSourceType() throws Exception {
    mockMvc
        .perform(
            post("/api/dev/projects/{projectId}/signals", PROJECT_ID)
                .header("API-Version", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "type": "risk",
                      "title": "제목",
                      "description": "설명",
                      "evidence": [{"source_type": "notion"}]
                    }
                    """))
        .andExpect(status().isBadRequest());

    verifyNoInteractions(devSignalWriter);
  }

  @Test
  @DisplayName("30자를 넘는 evidence details는 400으로 거부한다")
  void rejectsTooLongDetails() throws Exception {
    mockMvc
        .perform(
            post("/api/dev/projects/{projectId}/signals", PROJECT_ID)
                .header("API-Version", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "type": "risk",
                      "title": "제목",
                      "description": "설명",
                      "evidence": [{"source_type": "slack", "details": {"target": "%s"}}]
                    }
                    """
                        .formatted("가".repeat(31))))
        .andExpect(status().isBadRequest());

    verifyNoInteractions(devSignalWriter);
  }

  @Test
  @DisplayName("공백 title은 400으로 거부한다")
  void rejectsBlankTitle() throws Exception {
    mockMvc
        .perform(
            post("/api/dev/projects/{projectId}/signals", PROJECT_ID)
                .header("API-Version", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"type": "risk", "title": " ", "description": "설명"}
                    """))
        .andExpect(status().isBadRequest());

    verifyNoInteractions(devSignalWriter);
  }

  @TestConfiguration
  static class ApiVersioningTestConfig implements WebMvcConfigurer {
    @Override
    public void configureApiVersioning(ApiVersionConfigurer configurer) {
      configurer.useRequestHeader("API-Version").addSupportedVersions("1").setDefaultVersion("1");
    }
  }
}
