package works.momens.server.web.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import works.momens.server.auth.AccessTokenTestFactory;
import works.momens.server.common.test.AbstractPostgresIntegrationTest;
import works.momens.server.memory.ConfirmedMemoryCreator;
import works.momens.server.user.UserProfile;
import works.momens.server.user.UserService;

/**
 * 애플리케이션을 기동해 {@code POST /api/workspaces}를 검증하는 통합 테스트입니다(MOM-0897).
 *
 * <p>발급한 토큰과 PostgreSQL을 사용해 보안 체인, 응답, DB에 저장된 행을 확인합니다. 해당 endpoint는 네 테이블에 데이터를 저장하므로 개별 모듈
 * 테스트만으로는 전체 결과를 검증할 수 없습니다.
 *
 * <p>{@link ConfirmedMemoryCreator}는 {@code @MockitoSpyBean}으로 감쌉니다. 첫 번째 테스트에서는 기본 구현을 호출하고, 두 번째
 * 테스트에서만 예외를 발생시켜 트랜잭션 롤백을 검증합니다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class WebWorkspaceCreateIntegrationTest extends AbstractPostgresIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private AccessTokenTestFactory accessTokens;
  @Autowired private UserService userService;
  @Autowired private JdbcTemplate jdbcTemplate;

  @MockitoSpyBean private ConfirmedMemoryCreator confirmedMemoryCreator;

  @Test
  @DisplayName("워크스페이스를 생성하면 owner 멤버십, Welcome 프로젝트, 메모리 세 건을 함께 저장한다")
  void createsWorkspaceWithOwnerWelcomeProjectAndStarterMemories() throws Exception {
    UserProfile caller = userService.findOrCreate("web-it-create@momens.works", "신진수", null);

    String body =
        mockMvc
            .perform(
                authorized(post("/api/workspaces"), caller.id())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"Momens Create IT\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.name").value("Momens Create IT"))
            .andExpect(jsonPath("$.slug").value("momens-create-it"))
            .andExpect(jsonPath("$.description").doesNotExist())
            .andExpect(jsonPath("$.created_at").exists())
            .andReturn()
            .getResponse()
            .getContentAsString();
    UUID workspaceId = UUID.fromString(body.split("\"id\":\"")[1].split("\"")[0]);

    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT role FROM workspace_members WHERE workspace_id = ? AND user_id = ?",
                String.class,
                workspaceId,
                caller.id()))
        .isEqualTo("owner");

    Map<String, Object> project =
        jdbcTemplate.queryForMap(
            "SELECT id, label, name, description, status, health_status, progress, owner_id,"
                + " metadata::text AS metadata FROM projects WHERE workspace_id = ?",
            workspaceId);
    assertThat(project)
        .containsEntry("label", "PRJ-0001")
        .containsEntry("name", "Welcome")
        .containsEntry("description", "Default project · created with your workspace")
        .containsEntry("status", "active")
        .containsEntry("health_status", "open")
        .containsEntry("progress", 0)
        .containsEntry("owner_id", caller.id())
        .containsEntry("metadata", "{\"seeded\": true}");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT owner_user_id FROM project_owners WHERE project_id = ?",
                UUID.class,
                project.get("id")))
        .isEqualTo(caller.id());

    List<Map<String, Object>> memories =
        jdbcTemplate.queryForList(
            "SELECT label, memory_type, title, body, status, confirmed_by_user_id,"
                + " related_entity_ids::text AS related, metadata::text AS metadata"
                + " FROM confirmed_memories WHERE workspace_id = ? ORDER BY label",
            workspaceId);
    assertThat(memories).hasSize(3);
    assertThat(memories)
        .extracting(row -> row.get("label"), row -> row.get("memory_type"), row -> row.get("title"))
        .containsExactly(
            tuple("MEM-0001", "DECISION", "This is what a captured decision looks like"),
            tuple("MEM-0002", "OPEN_QUESTION", "Open questions stay visible until they resolve"),
            tuple("MEM-0003", "INSIGHT", "Add your own context any time"));
    assertThat(memories)
        .allSatisfy(
            row -> {
              assertThat(row.get("status")).isEqualTo("ACTIVE");
              assertThat(row.get("confirmed_by_user_id")).isEqualTo(caller.id());
              assertThat(row.get("metadata")).isEqualTo("{\"seeded\": true}");
              assertThat((String) row.get("related")).contains(project.get("id").toString());
            });
    assertThat(memories.get(2).get("body"))
        .isEqualTo("A note is the simplest memory — a thought you want the team to keep.");
  }

  @Test
  @DisplayName("메모리 저장에 실패하면 워크스페이스와 owner 멤버십까지 롤백한다")
  void rollsBackWorkspaceAndOwnerWhenStarterContentFails() throws Exception {
    UserProfile caller = userService.findOrCreate("web-it-rollback@momens.works", "신진수", null);
    willThrow(new IllegalStateException("starter memory 저장 실패"))
        .given(confirmedMemoryCreator)
        .create(any());

    mockMvc
        .perform(
            authorized(post("/api/workspaces"), caller.id())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Momens Rollback IT\"}"))
        .andExpect(status().is5xxServerError());

    // 워크스페이스와 프로젝트를 저장한 뒤 메모리 생성 단계에서 실패했는지 확인합니다.
    // 해당 호출을 검증하지 않으면 앞선 단계에서 실패한 요청도 아래 검증을 통과할 수 있습니다.
    verify(confirmedMemoryCreator).create(any());

    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM workspaces WHERE slug = ?",
                Integer.class,
                "momens-rollback-it"))
        .isZero();
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM workspace_members WHERE user_id = ?",
                Integer.class,
                caller.id()))
        .isZero();
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM projects WHERE name = ? AND owner_id = ?",
                Integer.class,
                "Welcome",
                caller.id()))
        .isZero();
  }

  private MockHttpServletRequestBuilder authorized(
      MockHttpServletRequestBuilder builder, UUID userId) {
    return builder
        .header("Authorization", "Bearer " + accessTokens.issueAccessToken(userId))
        .header("API-Version", "1");
  }
}
