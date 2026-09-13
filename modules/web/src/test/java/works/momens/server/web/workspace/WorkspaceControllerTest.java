package works.momens.server.web.workspace;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.security.Principal;
import java.time.Instant;
import java.util.List;
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
import works.momens.server.web.workspace.dto.response.WorkspaceResponse;
import works.momens.server.web.workspace.dto.response.WorkspaceSnapshotResponse;
import works.momens.server.workspace.core.WorkspaceDetail;
import works.momens.server.workspace.core.WorkspaceSlugAvailability;

/**
 * 컨트롤러가 Principal을 조합 서비스에 그대로 전달하고
 * 계약(docs/design/legacy-product-api-migration/slice-workspace-read.md 4.3)의 snake_case 응답 shape를
 * 내는지 검증합니다. versioning은 모듈 경계상 app 설정을 못 가져오므로 슬라이스 안에서 동일하게 구성합니다. 인증 실패(401)와 에러 응답 shape는 app의
 * 통합 테스트가 봅니다.
 */
@WebMvcTest(WorkspaceController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(WorkspaceControllerTest.ApiVersioningTestConfig.class)
class WorkspaceControllerTest {

  @Autowired private MockMvc mockMvc;
  @MockitoBean private WorkspaceService workspaceService;
  @MockitoBean private WorkspaceSnapshotService workspaceSnapshotService;

  private static final UUID USER_ID = UUID.fromString("5d2f7f3a-5db1-4f2c-8b9e-13607dd1f5e8");
  private static final UUID WORKSPACE_ID = UUID.fromString("30d9e9fe-f43b-4097-a88e-dc19f0a5b025");
  private final Principal principal = USER_ID::toString;

  @Test
  @DisplayName("목록은 workspaces 래퍼와 snake_case 필드로 응답한다")
  void listReturnsWrappedWorkspacesInSnakeCase() throws Exception {
    WorkspaceDetail detail =
        new WorkspaceDetail(
            WORKSPACE_ID,
            "Momens",
            "momens",
            "제품팀 워크스페이스",
            Instant.parse("2026-06-27T09:00:00Z"),
            Instant.parse("2026-06-27T09:00:00Z"));
    when(workspaceService.list(USER_ID)).thenReturn(List.of(detail));

    mockMvc
        .perform(get("/api/workspaces").principal(principal).header("API-Version", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.workspaces.length()").value(1))
        .andExpect(jsonPath("$.workspaces[0].id").value(WORKSPACE_ID.toString()))
        .andExpect(jsonPath("$.workspaces[0].name").value("Momens"))
        .andExpect(jsonPath("$.workspaces[0].slug").value("momens"))
        .andExpect(jsonPath("$.workspaces[0].description").value("제품팀 워크스페이스"))
        .andExpect(jsonPath("$.workspaces[0].created_at").value("2026-06-27T09:00:00Z"))
        .andExpect(jsonPath("$.workspaces[0].updated_at").value("2026-06-27T09:00:00Z"));
  }

  @Test
  @DisplayName("조회 결과가 없으면 null이 아니라 빈 배열로 응답한다")
  void listReturnsEmptyArrayWhenNoWorkspaces() throws Exception {
    when(workspaceService.list(USER_ID)).thenReturn(List.of());

    mockMvc
        .perform(get("/api/workspaces").principal(principal).header("API-Version", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.workspaces").isArray())
        .andExpect(jsonPath("$.workspaces.length()").value(0));
  }

  @Test
  @DisplayName("description이 없으면 응답에서 필드를 생략한다")
  void listOmitsDescriptionWhenNull() throws Exception {
    WorkspaceDetail detail =
        new WorkspaceDetail(WORKSPACE_ID, "Momens", "momens", null, Instant.now(), Instant.now());
    when(workspaceService.list(USER_ID)).thenReturn(List.of(detail));

    mockMvc
        .perform(get("/api/workspaces").principal(principal).header("API-Version", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.workspaces[0].description").doesNotExist());
  }

  @Test
  @DisplayName("단건 조회는 래퍼 없이 워크스페이스 객체를 응답한다")
  void getReturnsWorkspaceWithoutWrapper() throws Exception {
    WorkspaceDetail detail =
        new WorkspaceDetail(
            WORKSPACE_ID,
            "Momens",
            "momens",
            "제품팀 워크스페이스",
            Instant.parse("2026-06-27T09:00:00Z"),
            Instant.parse("2026-06-27T09:00:00Z"));
    when(workspaceService.get(eq(WORKSPACE_ID), eq(USER_ID))).thenReturn(detail);

    mockMvc
        .perform(
            get("/api/workspaces/{workspaceId}", WORKSPACE_ID)
                .principal(principal)
                .header("API-Version", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(WORKSPACE_ID.toString()))
        .andExpect(jsonPath("$.workspaces").doesNotExist());
  }

  @Test
  @DisplayName("snapshot은 9개 구획과 빈 컬렉션을 그대로 응답한다")
  void snapshotReturnsAllSectionsWithEmptyCollections() throws Exception {
    WorkspaceSnapshotResponse response =
        new WorkspaceSnapshotResponse(
            new WorkspaceResponse(
                WORKSPACE_ID, "Momens", "momens", null, Instant.now(), Instant.now()),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of());
    when(workspaceSnapshotService.get(eq(WORKSPACE_ID), eq(USER_ID))).thenReturn(response);

    mockMvc
        .perform(
            get("/api/workspaces/{workspaceId}/snapshot", WORKSPACE_ID)
                .principal(principal)
                .header("API-Version", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.workspace.id").value(WORKSPACE_ID.toString()))
        .andExpect(jsonPath("$.members").isArray())
        .andExpect(jsonPath("$.projects").isArray())
        .andExpect(jsonPath("$.milestones").isArray())
        .andExpect(jsonPath("$.tasks").isArray())
        .andExpect(jsonPath("$.blockers").isArray())
        .andExpect(jsonPath("$.memory_candidates").isArray())
        .andExpect(jsonPath("$.memories").isArray())
        .andExpect(jsonPath("$.task_contexts").isArray());
  }

  @Test
  @DisplayName("이미 사용 중인 slug는 사유와 대체 slug를 함께 반환한다")
  void slugAvailableReturnsReasonAndSuggestionWhenTaken() throws Exception {
    when(workspaceService.slugAvailability("momens"))
        .thenReturn(WorkspaceSlugAvailability.taken("momens", "momens-2"));

    mockMvc
        .perform(
            get("/api/workspaces/slug-available")
                .param("slug", "momens")
                .principal(principal)
                .header("API-Version", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.slug").value("momens"))
        .andExpect(jsonPath("$.available").value(false))
        .andExpect(jsonPath("$.reason").value("taken"))
        .andExpect(jsonPath("$.suggestion").value("momens-2"));
  }

  @Test
  @DisplayName("사용할 수 있는 slug는 사유와 대체 slug를 응답에서 생략한다")
  void slugAvailableOmitsReasonAndSuggestionWhenAvailable() throws Exception {
    when(workspaceService.slugAvailability("momens"))
        .thenReturn(WorkspaceSlugAvailability.available("momens"));

    mockMvc
        .perform(
            get("/api/workspaces/slug-available")
                .param("slug", "momens")
                .principal(principal)
                .header("API-Version", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.available").value(true))
        .andExpect(jsonPath("$.reason").doesNotExist())
        .andExpect(jsonPath("$.suggestion").doesNotExist());
  }

  @Test
  @DisplayName("slug-available 경로는 워크스페이스 식별자 경로보다 우선하며 slug 파라미터가 없어도 200을 반환한다")
  void slugAvailablePathWinsOverWorkspaceIdPathAndAcceptsMissingParameter() throws Exception {
    when(workspaceService.slugAvailability(null))
        .thenReturn(
            WorkspaceSlugAvailability.rejected("", WorkspaceSlugAvailability.Reason.INVALID));

    mockMvc
        .perform(
            get("/api/workspaces/slug-available").principal(principal).header("API-Version", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.slug").value(""))
        .andExpect(jsonPath("$.available").value(false))
        .andExpect(jsonPath("$.reason").value("invalid"));
  }

  @Test
  @DisplayName("수정 결과를 래퍼 없이 워크스페이스 객체로 응답한다")
  void updateReturnsUpdatedWorkspaceWithoutWrapper() throws Exception {
    WorkspaceDetail detail =
        new WorkspaceDetail(
            WORKSPACE_ID,
            "새 이름",
            "momens-2",
            null,
            Instant.parse("2026-06-27T09:00:00Z"),
            Instant.parse("2026-08-19T09:00:00Z"));
    when(workspaceService.update(WORKSPACE_ID, USER_ID, "새 이름", null, "momens-2"))
        .thenReturn(detail);

    mockMvc
        .perform(
            patch("/api/workspaces/{workspaceId}", WORKSPACE_ID)
                .principal(principal)
                .header("API-Version", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"새 이름\",\"slug\":\"momens-2\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(WORKSPACE_ID.toString()))
        .andExpect(jsonPath("$.name").value("새 이름"))
        .andExpect(jsonPath("$.slug").value("momens-2"))
        .andExpect(jsonPath("$.description").doesNotExist())
        .andExpect(jsonPath("$.updated_at").value("2026-08-19T09:00:00Z"));
  }

  @Test
  @DisplayName("요청 본문이 비어 있어도 200으로 응답한다")
  void updateAcceptsEmptyBody() throws Exception {
    when(workspaceService.update(WORKSPACE_ID, USER_ID, null, null, null))
        .thenReturn(
            new WorkspaceDetail(
                WORKSPACE_ID, "Momens", "momens", null, Instant.now(), Instant.now()));

    mockMvc
        .perform(
            patch("/api/workspaces/{workspaceId}", WORKSPACE_ID)
                .principal(principal)
                .header("API-Version", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.slug").value("momens"));
  }

  @TestConfiguration
  static class ApiVersioningTestConfig implements WebMvcConfigurer {
    @Override
    public void configureApiVersioning(ApiVersionConfigurer configurer) {
      configurer.useRequestHeader("API-Version").addSupportedVersions("1").setDefaultVersion("1");
    }
  }
}
