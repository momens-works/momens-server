package works.momens.server.web.workspace;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
import works.momens.server.workspace.membership.AssignableWorkspaceRole;

/**
 * 컨트롤러가 Principal의 사용자 ID를 조합 서비스에 전달하고, 레거시와 동일한 성공 응답 형식을 반환하는지 검증합니다.
 *
 * <p>멤버 목록은 {@code members}로 감싼 snake_case 형식으로 응답하고, 역할 변경과 멤버 제거는 {@code message}만 반환합니다. 인증 실패와
 * 에러 응답 형식은 app 통합 테스트에서 검증합니다.
 */
@WebMvcTest(WorkspaceMemberController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(WorkspaceMemberControllerTest.ApiVersioningTestConfig.class)
class WorkspaceMemberControllerTest {

  @Autowired private MockMvc mockMvc;
  @MockitoBean private WorkspaceMemberService workspaceMemberService;

  private static final UUID USER_ID = UUID.fromString("5d2f7f3a-5db1-4f2c-8b9e-13607dd1f5e8");
  private static final UUID WORKSPACE_ID = UUID.fromString("30d9e9fe-f43b-4097-a88e-dc19f0a5b025");
  private static final UUID TARGET_ID = UUID.fromString("9d0a1a51-7f5f-4c6a-9b7a-1c0b4d5e6f70");
  private final Principal principal = USER_ID::toString;

  @Test
  @DisplayName("멤버 목록은 members 래퍼와 snake_case 필드로 응답한다")
  void listReturnsWrappedMembersInSnakeCase() throws Exception {
    WorkspaceMemberView view =
        new WorkspaceMemberView(
            TARGET_ID,
            "kyuil@momens.works",
            "김규일",
            "admin",
            Instant.parse("2026-06-27T09:00:00Z"),
            Instant.parse("2026-07-01T09:00:00Z"));
    when(workspaceMemberService.list(eq(WORKSPACE_ID), eq(USER_ID))).thenReturn(List.of(view));

    mockMvc
        .perform(
            get("/api/workspaces/{workspaceId}/members", WORKSPACE_ID)
                .principal(principal)
                .header("API-Version", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.members.length()").value(1))
        .andExpect(jsonPath("$.members[0].id").value(TARGET_ID.toString()))
        .andExpect(jsonPath("$.members[0].email").value("kyuil@momens.works"))
        .andExpect(jsonPath("$.members[0].name").value("김규일"))
        .andExpect(jsonPath("$.members[0].role").value("admin"))
        .andExpect(jsonPath("$.members[0].created_at").value("2026-06-27T09:00:00Z"))
        .andExpect(jsonPath("$.members[0].updated_at").value("2026-07-01T09:00:00Z"));
  }

  @Test
  @DisplayName("멤버가 없으면 빈 배열로 응답한다")
  void listReturnsEmptyArrayWhenNoMembers() throws Exception {
    when(workspaceMemberService.list(eq(WORKSPACE_ID), eq(USER_ID))).thenReturn(List.of());

    mockMvc
        .perform(
            get("/api/workspaces/{workspaceId}/members", WORKSPACE_ID)
                .principal(principal)
                .header("API-Version", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.members").isArray())
        .andExpect(jsonPath("$.members.length()").value(0));
  }

  @Test
  @DisplayName("역할 변경은 요청한 역할을 서비스에 전달하고 updated 메시지로 응답한다")
  void updatePassesRoleToServiceAndReturnsUpdatedMessage() throws Exception {
    mockMvc
        .perform(
            patch("/api/workspaces/{workspaceId}/members/{userId}", WORKSPACE_ID, TARGET_ID)
                .principal(principal)
                .header("API-Version", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"admin\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.message").value("updated"));

    verify(workspaceMemberService)
        .changeRole(WORKSPACE_ID, USER_ID, TARGET_ID, AssignableWorkspaceRole.ADMIN);
  }

  @Test
  @DisplayName("멤버 제거는 대상 사용자 ID를 서비스에 전달하고 removed 메시지로 응답한다")
  void removePassesTargetToServiceAndReturnsRemovedMessage() throws Exception {
    mockMvc
        .perform(
            delete("/api/workspaces/{workspaceId}/members/{userId}", WORKSPACE_ID, TARGET_ID)
                .principal(principal)
                .header("API-Version", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.message").value("removed"));

    verify(workspaceMemberService).remove(WORKSPACE_ID, USER_ID, TARGET_ID);
  }

  @TestConfiguration
  static class ApiVersioningTestConfig implements WebMvcConfigurer {
    @Override
    public void configureApiVersioning(ApiVersionConfigurer configurer) {
      configurer.useRequestHeader("API-Version").addSupportedVersions("1").setDefaultVersion("1");
    }
  }
}
