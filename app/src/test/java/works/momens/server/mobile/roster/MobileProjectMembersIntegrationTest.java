package works.momens.server.mobile.roster;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import works.momens.server.auth.AccessTokenTestFactory;
import works.momens.server.common.test.AbstractPostgresIntegrationTest;
import works.momens.server.user.UserProfile;
import works.momens.server.user.UserService;

/** 실제 토큰·DB로 멤버 검색과 정렬을 검증합니다. SQL로 fixture를 구성하며 부분 일치·대소문자 무시·이름 정렬은 기획 확인이 남은 가결정입니다. */
@SpringBootTest
@AutoConfigureMockMvc
class MobileProjectMembersIntegrationTest extends AbstractPostgresIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private AccessTokenTestFactory accessTokens;
  @Autowired private UserService userService;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void returnsWorkspaceMembersSortedByName() throws Exception {
    UserProfile jinsu = userService.findOrCreate("members-it-jinsu@momens.works", "신진수", null);
    UserProfile gyuilEng =
        userService.findOrCreate(
            "members-it-gyuil-eng@momens.works", "Gyuil Kim", "https://a/gyuil.png");
    UserProfile gyuil = userService.findOrCreate("members-it-gyuil@momens.works", "김규일", null);
    UserProfile outsider = userService.findOrCreate("members-it-out@momens.works", "박외부", null);

    UUID workspace = insertWorkspace("members-sorted");
    UUID otherWorkspace = insertWorkspace("members-sorted-other");
    addMember(workspace, jinsu.id(), "owner");
    addMember(workspace, gyuilEng.id(), "member");
    addMember(workspace, gyuil.id(), "member");
    addMember(otherWorkspace, outsider.id(), "owner");
    UUID project = insertProject(workspace, jinsu.id(), "members-project");

    // 이름 오름차순은 단순 문자열 비교라 라틴 문자가 한글보다 앞에 온다.
    mockMvc
        .perform(
            get("/api/mobile/projects/{projectId}/members", project)
                .header("Authorization", "Bearer " + accessTokens.issueAccessToken(jinsu.id()))
                .header("API-Version", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.members.length()").value(3))
        .andExpect(jsonPath("$.members[0].name").value("Gyuil Kim"))
        .andExpect(jsonPath("$.members[0].avatar_url").value("https://a/gyuil.png"))
        .andExpect(jsonPath("$.members[1].name").value("김규일"))
        .andExpect(jsonPath("$.members[1].avatar_url", nullValue()))
        .andExpect(jsonPath("$.members[2].name").value("신진수"));
  }

  @Test
  void filtersMembersByNameIgnoringCase() throws Exception {
    UserProfile jinsu =
        userService.findOrCreate("members-it-filter-jinsu@momens.works", "신진수", null);
    UserProfile gyuilEng =
        userService.findOrCreate("members-it-filter-gyuil@momens.works", "Gyuil Kim", null);
    UUID workspace = insertWorkspace("members-filter");
    addMember(workspace, jinsu.id(), "owner");
    addMember(workspace, gyuilEng.id(), "member");
    UUID project = insertProject(workspace, jinsu.id(), "members-filter-project");

    mockMvc
        .perform(
            get("/api/mobile/projects/{projectId}/members", project)
                .param("query", "gYuIl")
                .header("Authorization", "Bearer " + accessTokens.issueAccessToken(jinsu.id()))
                .header("API-Version", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.members.length()").value(1))
        .andExpect(jsonPath("$.members[0].id").value(gyuilEng.id().toString()));
  }

  @Test
  void returnsForbiddenWhenCallerIsNotWorkspaceMember() throws Exception {
    UserProfile gyuil =
        userService.findOrCreate("members-it-owner-gyuil@momens.works", "김규일", null);
    UserProfile jinsu =
        userService.findOrCreate("members-it-stranger-jinsu@momens.works", "신진수", null);
    UUID workspace = insertWorkspace("members-forbidden");
    addMember(workspace, gyuil.id(), "owner");
    UUID project = insertProject(workspace, gyuil.id(), "members-forbidden-project");

    mockMvc
        .perform(
            get("/api/mobile/projects/{projectId}/members", project)
                .header("Authorization", "Bearer " + accessTokens.issueAccessToken(jinsu.id()))
                .header("API-Version", "1"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("AUTH_FORBIDDEN"));
  }

  @Test
  void returnsNotFoundForUnknownProject() throws Exception {
    UserProfile caller = userService.findOrCreate("members-it-404@momens.works", "신진수", null);

    mockMvc
        .perform(
            get("/api/mobile/projects/{projectId}/members", UUID.randomUUID())
                .header("Authorization", "Bearer " + accessTokens.issueAccessToken(caller.id()))
                .header("API-Version", "1"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("PROJECT_NOT_FOUND"));
  }

  @Test
  void returnsStandardUnauthorizedWithoutToken() throws Exception {
    mockMvc
        .perform(
            get("/api/mobile/projects/{projectId}/members", UUID.randomUUID())
                .header("API-Version", "1"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error.code").value("AUTH_UNAUTHORIZED"));
  }

  private UUID insertWorkspace(String slug) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO workspaces (id, name, slug) VALUES (?, ?, ?)", id, "모멘스", slug);
    return id;
  }

  private void addMember(UUID workspaceId, UUID userId, String role) {
    jdbcTemplate.update(
        "INSERT INTO workspace_members (workspace_id, user_id, role) VALUES (?, ?, ?)",
        workspaceId,
        userId,
        role);
  }

  private UUID insertProject(UUID workspaceId, UUID ownerId, String name) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO projects (id, workspace_id, name, owner_id) VALUES (?, ?, ?, ?)",
        id,
        workspaceId,
        name,
        ownerId);
    return id;
  }
}
