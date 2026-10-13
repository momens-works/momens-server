package works.momens.server.mobile.bootstrap;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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

/**
 * 실제 토큰·DB로 bootstrap을 검증합니다. 생성 시각을 제어하기 위해 SQL로 시드합니다. 프로젝트 0개의 200·null·빈 배열 정책은 기획 확인이 남은
 * 가결정입니다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MobileBootstrapIntegrationTest extends AbstractPostgresIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private AccessTokenTestFactory accessTokens;
  @Autowired private UserService userService;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void returnsEntryContextWithNewestProjectAsDefault() throws Exception {
    UserProfile user = userService.findOrCreate("bootstrap-it@momens.works", "김민지", null);
    UUID ownedWorkspace = insertWorkspace("bootstrap-owned");
    UUID joinedWorkspace = insertWorkspace("bootstrap-joined");
    UUID otherWorkspace = insertWorkspace("bootstrap-others");
    addMember(ownedWorkspace, user.id(), "owner");
    addMember(joinedWorkspace, user.id(), "member");

    Instant now = Instant.now();
    UUID older = insertProject(ownedWorkspace, user.id(), "older", now.minus(1, ChronoUnit.DAYS));
    UUID newest = insertProject(joinedWorkspace, user.id(), "newest", now);
    UUID deleted =
        insertProject(ownedWorkspace, user.id(), "deleted", now.plus(1, ChronoUnit.DAYS));
    jdbcTemplate.update("UPDATE projects SET deleted_at = NOW() WHERE id = ?", deleted);
    insertProject(otherWorkspace, user.id(), "not-mine", now);

    String token = accessTokens.issueAccessToken(user.id());

    mockMvc
        .perform(
            get("/api/mobile/bootstrap")
                .header("Authorization", "Bearer " + token)
                .header("API-Version", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.me.id").value(user.id().toString()))
        .andExpect(jsonPath("$.me.email").value("bootstrap-it@momens.works"))
        .andExpect(jsonPath("$.me.name").value("김민지"))
        .andExpect(jsonPath("$.me.avatar_url", nullValue()))
        .andExpect(jsonPath("$.default_project_id").value(newest.toString()))
        .andExpect(jsonPath("$.projects.length()").value(2))
        .andExpect(jsonPath("$.projects[0].id").value(newest.toString()))
        .andExpect(jsonPath("$.projects[0].role").value("member"))
        .andExpect(jsonPath("$.projects[1].id").value(older.toString()))
        .andExpect(jsonPath("$.projects[1].role").value("owner"));
  }

  @Test
  void returnsNullDefaultAndEmptyProjectsForUserWithoutProjects() throws Exception {
    UserProfile user = userService.findOrCreate("bootstrap-empty@momens.works", "혼자", null);
    String token = accessTokens.issueAccessToken(user.id());

    mockMvc
        .perform(
            get("/api/mobile/bootstrap")
                .header("Authorization", "Bearer " + token)
                .header("API-Version", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.me.id").value(user.id().toString()))
        .andExpect(jsonPath("$.default_project_id", nullValue()))
        .andExpect(jsonPath("$.projects").isEmpty());
  }

  @Test
  void returnsStandardUnauthorizedWithoutToken() throws Exception {
    mockMvc
        .perform(get("/api/mobile/bootstrap").header("API-Version", "1"))
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

  /** 기본 project 선정 규칙(생성 최신순 첫 번째)을 검증하려고 created_at을 명시해 삽입합니다. */
  private UUID insertProject(UUID workspaceId, UUID ownerId, String name, Instant createdAt) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO projects (id, workspace_id, name, owner_id, created_at, updated_at)"
            + " VALUES (?, ?, ?, ?, ?, ?)",
        id,
        workspaceId,
        name,
        ownerId,
        Timestamp.from(createdAt),
        Timestamp.from(createdAt));
    return id;
  }
}
