package works.momens.server.web.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import works.momens.server.auth.AccessTokenTestFactory;
import works.momens.server.common.test.AbstractPostgresIntegrationTest;
import works.momens.server.user.UserProfile;
import works.momens.server.user.UserService;

/**
 * 워크스페이스 멤버 endpoint 세 개를 애플리케이션 실행 환경에서 검증하는 통합 테스트입니다(MOM-0864).
 *
 * <p>실제 토큰과 PostgreSQL을 사용해 보안 필터 체인부터 응답 형식, 정렬 순서, 요청자의 역할에 따른 응답, 에러 코드까지 전체 흐름을 검증합니다. 사용자는
 * user 모듈의 public API로 생성하고, 워크스페이스와 멤버십은 생성 endpoint가 아직 없으므로 SQL로 직접 저장합니다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class WebWorkspaceMembersIntegrationTest extends AbstractPostgresIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private AccessTokenTestFactory accessTokens;
  @Autowired private UserService userService;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  @DisplayName("멤버 목록을 가입 시각 오름차순으로 응답한다")
  void listReturnsMembersSortedByJoinedAt() throws Exception {
    UUID workspaceId = insertWorkspace("web-members-sorted");
    UserProfile caller = user("members-sorted-caller@momens.works", "나중에 오는 이름");
    UserProfile joinedSecond = user("members-sorted-second@momens.works", "다음에 오는 이름");
    UserProfile joinedLast = user("members-sorted-third@momens.works", "가장 먼저 오는 이름");
    addMember(workspaceId, caller.id(), "owner", Instant.parse("2026-08-01T00:00:00Z"));
    addMember(workspaceId, joinedSecond.id(), "admin", Instant.parse("2026-08-05T00:00:00Z"));
    addMember(workspaceId, joinedLast.id(), "member", Instant.parse("2026-08-10T00:00:00Z"));

    mockMvc
        .perform(authorized(get("/api/workspaces/{id}/members", workspaceId), caller.id()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.members.length()").value(3))
        .andExpect(jsonPath("$.members[0].id").value(caller.id().toString()))
        .andExpect(jsonPath("$.members[0].email").value("members-sorted-caller@momens.works"))
        .andExpect(jsonPath("$.members[0].role").value("owner"))
        .andExpect(jsonPath("$.members[0].created_at").exists())
        .andExpect(jsonPath("$.members[0].updated_at").exists())
        .andExpect(jsonPath("$.members[1].id").value(joinedSecond.id().toString()))
        .andExpect(jsonPath("$.members[2].id").value(joinedLast.id().toString()));
  }

  @Test
  @DisplayName("멤버가 아닌 요청자는 멤버 목록을 조회할 수 없다")
  void listRejectsCallerWhoIsNotMember() throws Exception {
    UUID workspaceId = insertWorkspace("web-members-stranger");
    UserProfile stranger = user("members-stranger@momens.works", "비멤버");
    addMember(workspaceId, user("members-owner@momens.works", "소유자").id(), "owner");

    mockMvc
        .perform(authorized(get("/api/workspaces/{id}/members", workspaceId), stranger.id()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("AUTH_FORBIDDEN"))
        .andExpect(jsonPath("$.error.details.workspace_id").value(workspaceId.toString()))
        .andExpect(jsonPath("$.error.details.required_role").value("member"));
  }

  @Test
  @DisplayName("워크스페이스가 없으면 404로 응답한다")
  void listReturnsNotFoundForMissingWorkspace() throws Exception {
    UserProfile caller = user("members-missing-ws@momens.works", "요청자");

    mockMvc
        .perform(authorized(get("/api/workspaces/{id}/members", UUID.randomUUID()), caller.id()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("WORKSPACE_NOT_FOUND"));
  }

  @Test
  @DisplayName("역할 변경을 저장하고 updated 메시지로 응답한다")
  void updateChangesRoleAndReturnsUpdatedMessage() throws Exception {
    UUID workspaceId = insertWorkspace("web-members-promote");
    UserProfile admin = user("members-promote-admin@momens.works", "관리자");
    UserProfile target = user("members-promote-target@momens.works", "대상자");
    addMember(workspaceId, admin.id(), "admin");
    addMember(workspaceId, target.id(), "member");

    mockMvc
        .perform(
            authorized(
                    patch("/api/workspaces/{id}/members/{userId}", workspaceId, target.id()),
                    admin.id())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"admin\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.message").value("updated"));

    assertThat(readRole(workspaceId, target.id())).isEqualTo("admin");
  }

  @Test
  @DisplayName("요청자의 역할이 member이면 403과 필요한 최소 역할을 함께 응답한다")
  void updateRejectsCallerWithMemberRole() throws Exception {
    UUID workspaceId = insertWorkspace("web-members-promote-denied");
    UserProfile caller = user("members-promote-denied@momens.works", "일반 멤버");
    UserProfile target = user("members-promote-denied-target@momens.works", "대상자");
    addMember(workspaceId, caller.id(), "member");
    addMember(workspaceId, target.id(), "member");

    mockMvc
        .perform(
            authorized(
                    patch("/api/workspaces/{id}/members/{userId}", workspaceId, target.id()),
                    caller.id())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"admin\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("AUTH_FORBIDDEN"))
        .andExpect(jsonPath("$.error.details.required_role").value("admin"));
  }

  @Test
  @DisplayName("대상이 owner이면 409로 응답한다")
  void updateRejectsOwnerTarget() throws Exception {
    UUID workspaceId = insertWorkspace("web-members-owner-protected");
    UserProfile admin = user("members-owner-protected-admin@momens.works", "관리자");
    UserProfile owner = user("members-owner-protected-owner@momens.works", "소유자");
    addMember(workspaceId, admin.id(), "admin");
    addMember(workspaceId, owner.id(), "owner");

    mockMvc
        .perform(
            authorized(
                    patch("/api/workspaces/{id}/members/{userId}", workspaceId, owner.id()),
                    admin.id())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"member\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("WORKSPACE_OWNER_PROTECTED"));
  }

  @Test
  @DisplayName("owner를 부여하려는 요청은 400으로 응답한다")
  void updateRejectsOwnerRoleValue() throws Exception {
    UUID workspaceId = insertWorkspace("web-members-invalid-role");
    UserProfile admin = user("members-invalid-role-admin@momens.works", "관리자");
    UserProfile target = user("members-invalid-role-target@momens.works", "대상자");
    addMember(workspaceId, admin.id(), "admin");
    addMember(workspaceId, target.id(), "member");

    mockMvc
        .perform(
            authorized(
                    patch("/api/workspaces/{id}/members/{userId}", workspaceId, target.id()),
                    admin.id())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"owner\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("WORKSPACE_INVALID_ROLE"));
  }

  @Test
  @DisplayName("대상이 멤버가 아니면 404로 응답한다")
  void updateReturnsNotFoundWhenTargetIsNotMember() throws Exception {
    UUID workspaceId = insertWorkspace("web-members-target-missing");
    UserProfile admin = user("members-target-missing-admin@momens.works", "관리자");
    UserProfile stranger = user("members-target-missing-stranger@momens.works", "비멤버");
    addMember(workspaceId, admin.id(), "admin");

    mockMvc
        .perform(
            authorized(
                    patch("/api/workspaces/{id}/members/{userId}", workspaceId, stranger.id()),
                    admin.id())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"admin\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("WORKSPACE_MEMBER_NOT_FOUND"));
  }

  @Test
  @DisplayName("멤버 제거를 저장하고 removed 메시지로 응답한다")
  void removeDeletesMemberAndReturnsRemovedMessage() throws Exception {
    UUID workspaceId = insertWorkspace("web-members-remove");
    UserProfile admin = user("members-remove-admin@momens.works", "관리자");
    UserProfile target = user("members-remove-target@momens.works", "대상자");
    addMember(workspaceId, admin.id(), "admin");
    addMember(workspaceId, target.id(), "member");

    mockMvc
        .perform(
            authorized(
                delete("/api/workspaces/{id}/members/{userId}", workspaceId, target.id()),
                admin.id()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.message").value("removed"));

    assertThat(readRoles(workspaceId)).containsExactly("admin");
  }

  @Test
  @DisplayName("자기 자신을 제거하려는 요청은 409로 응답한다")
  void removeRejectsSelf() throws Exception {
    UUID workspaceId = insertWorkspace("web-members-remove-self");
    UserProfile admin = user("members-remove-self@momens.works", "관리자");
    addMember(workspaceId, admin.id(), "admin");

    mockMvc
        .perform(
            authorized(
                delete("/api/workspaces/{id}/members/{userId}", workspaceId, admin.id()),
                admin.id()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("WORKSPACE_SELF_REMOVAL_NOT_ALLOWED"));
  }

  @Test
  @DisplayName("대상이 owner이면 409로 응답한다")
  void removeRejectsOwnerTarget() throws Exception {
    UUID workspaceId = insertWorkspace("web-members-remove-owner");
    UserProfile admin = user("members-remove-owner-admin@momens.works", "관리자");
    UserProfile owner = user("members-remove-owner-owner@momens.works", "소유자");
    addMember(workspaceId, admin.id(), "admin");
    addMember(workspaceId, owner.id(), "owner");

    mockMvc
        .perform(
            authorized(
                delete("/api/workspaces/{id}/members/{userId}", workspaceId, owner.id()),
                admin.id()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("WORKSPACE_OWNER_PROTECTED"));
  }

  @Test
  @DisplayName("워크스페이스가 없으면 404로 응답한다")
  void removeReturnsNotFoundForMissingWorkspace() throws Exception {
    UserProfile caller = user("members-remove-missing-ws@momens.works", "요청자");

    mockMvc
        .perform(
            authorized(
                delete(
                    "/api/workspaces/{id}/members/{userId}", UUID.randomUUID(), UUID.randomUUID()),
                caller.id()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("WORKSPACE_NOT_FOUND"));
  }

  private MockHttpServletRequestBuilder authorized(
      MockHttpServletRequestBuilder builder, UUID userId) {
    return builder
        .header("Authorization", "Bearer " + accessTokens.issueAccessToken(userId))
        .header("API-Version", "1");
  }

  private UserProfile user(String email, String name) {
    return userService.findOrCreate(email, name, null);
  }

  private UUID insertWorkspace(String slug) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO workspaces (id, name, slug) VALUES (?, ?, ?)", id, "모먼스", slug);
    return id;
  }

  private void addMember(UUID workspaceId, UUID userId, String role) {
    jdbcTemplate.update(
        "INSERT INTO workspace_members (workspace_id, user_id, role) VALUES (?, ?, ?)",
        workspaceId,
        userId,
        role);
  }

  private void addMember(UUID workspaceId, UUID userId, String role, Instant joinedAt) {
    jdbcTemplate.update(
        "INSERT INTO workspace_members (workspace_id, user_id, role, created_at, updated_at)"
            + " VALUES (?, ?, ?, ?, ?)",
        workspaceId,
        userId,
        role,
        Timestamp.from(joinedAt),
        Timestamp.from(joinedAt));
  }

  private String readRole(UUID workspaceId, UUID userId) {
    return jdbcTemplate.queryForObject(
        "SELECT role FROM workspace_members WHERE workspace_id = ? AND user_id = ?",
        String.class,
        workspaceId,
        userId);
  }

  private List<String> readRoles(UUID workspaceId) {
    return jdbcTemplate.queryForList(
        "SELECT role FROM workspace_members WHERE workspace_id = ?", String.class, workspaceId);
  }
}
