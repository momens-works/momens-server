package works.momens.server.mobile.brief;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.security.Principal;
import java.time.LocalDate;
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
import works.momens.server.project.core.ProjectSnapshot;

/**
 * 컨트롤러가 경로 변수와 쿼리 파라미터, Principal을 조합 서비스에 그대로 전달하고 snake_case 응답 shape를 내는지 검증합니다. versioning은 모듈
 * 경계상 app 설정을 못 가져오므로 슬라이스 안에서 동일하게 구성합니다. 인증 실패(401)와 에러 응답 shape는 app의 통합 테스트가 봅니다.
 */
@WebMvcTest(ProjectBriefController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(ProjectBriefControllerTest.ApiVersioningTestConfig.class)
class ProjectBriefControllerTest {

  @Autowired private MockMvc mockMvc;
  @MockitoBean private ProjectBriefService projectBriefService;

  private static final UUID USER_ID = UUID.fromString("5d2f7f3a-5db1-4f2c-8b9e-13607dd1f5e8");
  private static final UUID PROJECT_ID = UUID.fromString("30d9e9fe-f43b-4097-a88e-dc19f0a5b025");
  private static final UUID WORKSPACE_ID = UUID.fromString("9be5a41c-2c63-4f7e-9d15-3b7f8c2a1d40");
  private static final UUID SIGNAL_ID = UUID.fromString("6f3d8a61-4de7-4c01-9d2b-16fdf182e9a1");
  private static final UUID TASK_ID = UUID.fromString("27afd507-9c7f-4f0d-a2be-fcdab2477b19");
  private final Principal principal = USER_ID::toString;

  @Test
  void getBriefReturnsSnakeCaseBrief() throws Exception {
    when(projectBriefService.getBrief(eq(PROJECT_ID), eq(USER_ID)))
        .thenReturn(
            new MobileBrief(
                new ProjectSnapshot(
                    PROJECT_ID,
                    WORKSPACE_ID,
                    "Q2 Activation Readiness",
                    LocalDate.of(2026, 6, 30),
                    "목표일까지 Q2 Activation Readiness 범위의 회원 가입 MVP를 안정적으로 릴리즈한다."),
                64,
                "Android 권한 요청 이슈가 발견되었으며, 소셜 로그인은 MVP 범위에서 제외되었습니다.",
                List.of(
                    new MobileBrief.FilterCount("all", "All", 12L),
                    new MobileBrief.FilterCount("risk", "Risk", 1L),
                    new MobileBrief.FilterCount("change", "Change", 7L),
                    new MobileBrief.FilterCount("decision", "Decision", 2L),
                    new MobileBrief.FilterCount("question", "Question", 2L)),
                List.of(new MobileBrief.SignalItem(SIGNAL_ID, "change", "권한 요청 반복 문의")),
                "cursor-1",
                List.of(new MobileBrief.Priority(1, "이메일 회원가입 완료율 개선", TASK_ID))));

    mockMvc
        .perform(
            get("/api/mobile/projects/{projectId}/brief", PROJECT_ID)
                .principal(principal)
                .header("API-Version", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.project.id").value(PROJECT_ID.toString()))
        .andExpect(jsonPath("$.project.name").value("Q2 Activation Readiness"))
        .andExpect(jsonPath("$.project.target_date").value("2026-06-30"))
        .andExpect(jsonPath("$.project.progress").value(64))
        .andExpect(
            jsonPath("$.project.summary")
                .value("목표일까지 Q2 Activation Readiness 범위의 회원 가입 MVP를 안정적으로 릴리즈한다."))
        // 시그널 요약 문단은 backing source가 없어 null로 항상 포함된다(합성 필드 정책).
        .andExpect(
            jsonPath("$.signal_summary.summary")
                .value("Android 권한 요청 이슈가 발견되었으며, 소셜 로그인은 MVP 범위에서 제외되었습니다."))
        .andExpect(jsonPath("$.signal_summary.filters.length()").value(5))
        .andExpect(jsonPath("$.signal_summary.filters[0].key").value("all"))
        .andExpect(jsonPath("$.signal_summary.filters[0].label").value("All"))
        .andExpect(jsonPath("$.signal_summary.filters[0].count").value(12))
        .andExpect(jsonPath("$.signal_summary.filters[2].key").value("change"))
        .andExpect(jsonPath("$.signal_summary.filters[2].label").value("Change"))
        .andExpect(jsonPath("$.signal_summary.filters[2].count").value(7))
        .andExpect(jsonPath("$.signal_summary.items.length()").value(1))
        .andExpect(jsonPath("$.signal_summary.items[0].id").value(SIGNAL_ID.toString()))
        .andExpect(jsonPath("$.signal_summary.items[0].type").value("change"))
        .andExpect(jsonPath("$.signal_summary.items[0].title").value("권한 요청 반복 문의"))
        .andExpect(jsonPath("$.signal_summary.next_cursor").value("cursor-1"))
        .andExpect(jsonPath("$.priorities.length()").value(1))
        .andExpect(jsonPath("$.priorities[0].rank").value(1))
        .andExpect(jsonPath("$.priorities[0].title").value("이메일 회원가입 완료율 개선"))
        .andExpect(jsonPath("$.priorities[0].task_id").value(TASK_ID.toString()));
  }

  @Test
  void getSignalSummaryPagePassesQueryParamsThrough() throws Exception {
    when(projectBriefService.getSignalSummaryPage(
            eq(PROJECT_ID), eq(USER_ID), eq("change"), eq("cursor-1"), eq(5)))
        .thenReturn(
            new MobileBriefSignalPage(
                List.of(new MobileBrief.SignalItem(SIGNAL_ID, "change", "VOC 문의")), null));

    mockMvc
        .perform(
            get("/api/mobile/projects/{projectId}/brief/signal-summary", PROJECT_ID)
                .param("filter", "change")
                .param("cursor", "cursor-1")
                .param("limit", "5")
                .principal(principal)
                .header("API-Version", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].id").value(SIGNAL_ID.toString()))
        .andExpect(jsonPath("$.next_cursor", nullValue()));
  }

  @Test
  void getSignalSummaryPageDefaultsFilterToAll() throws Exception {
    when(projectBriefService.getSignalSummaryPage(
            eq(PROJECT_ID), eq(USER_ID), eq("all"), isNull(), isNull()))
        .thenReturn(new MobileBriefSignalPage(List.of(), null));

    mockMvc
        .perform(
            get("/api/mobile/projects/{projectId}/brief/signal-summary", PROJECT_ID)
                .principal(principal)
                .header("API-Version", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(0));
  }

  @TestConfiguration
  static class ApiVersioningTestConfig implements WebMvcConfigurer {
    @Override
    public void configureApiVersioning(ApiVersionConfigurer configurer) {
      configurer.useRequestHeader("API-Version").addSupportedVersions("1").setDefaultVersion("1");
    }
  }
}
