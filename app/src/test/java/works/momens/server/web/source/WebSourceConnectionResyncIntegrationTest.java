package works.momens.server.web.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import works.momens.server.auth.AccessTokenTestFactory;
import works.momens.server.common.api.BusinessException;
import works.momens.server.common.test.AbstractPostgresIntegrationTest;
import works.momens.server.source.SourceConnectionWriter;
import works.momens.server.user.UserService;

@SpringBootTest
@AutoConfigureMockMvc
class WebSourceConnectionResyncIntegrationTest extends AbstractPostgresIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private AccessTokenTestFactory accessTokens;
  @Autowired private UserService userService;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private SourceConnectionWriter writer;

  @ParameterizedTest
  @ValueSource(strings = {"PENDING", "ACTIVE", "DISABLED", "ERROR", "REVOKED"})
  @DisplayName("관리자는 모든 연결 상태에서 재동기화를 반복 요청할 수 있다")
  void adminCanRequestInEveryStateAndRepeat(String connectionStatus) throws Exception {
    UUID workspaceId = workspace();
    UUID caller = user();
    member(workspaceId, caller, "admin");
    UUID connectionId = connection(workspaceId, connectionStatus);
    Map<String, Object> before = row(connectionId);
    Instant started = databaseNow();

    mockMvc
        .perform(request(connectionId, caller))
        .andExpect(status().isOk())
        .andExpect(content().json("{\"message\":\"resync requested\"}"));

    Map<String, Object> first = row(connectionId);
    Instant requested = ((Timestamp) first.get("resync_requested_at")).toInstant();
    assertThat(requested).isBetween(started, databaseNow());
    assertThat(first.get("updated_at")).isEqualTo(first.get("resync_requested_at"));
    assertThat(requested).isAfter(((Timestamp) first.get("last_synced_at")).toInstant());
    assertOnlyRequestTimesChanged(before, first);

    mockMvc.perform(request(connectionId, caller)).andExpect(status().isOk());
    Map<String, Object> second = row(connectionId);
    assertThat(((Timestamp) second.get("resync_requested_at")).toInstant()).isAfter(requested);
    assertThat(second.get("updated_at")).isEqualTo(second.get("resync_requested_at"));
    assertOnlyRequestTimesChanged(first, second);
  }

  @Test
  @DisplayName("소유자는 최초 동기화 전에도 재동기화를 요청할 수 있다")
  void ownerCanRequestBeforeFirstSync() throws Exception {
    UUID workspaceId = workspace();
    UUID caller = user();
    member(workspaceId, caller, "owner");
    UUID connectionId = connection(workspaceId, "ACTIVE");
    jdbcTemplate.update(
        "UPDATE source_connections SET last_synced_at = NULL WHERE id = ?", connectionId);
    mockMvc.perform(request(connectionId, caller)).andExpect(status().isOk());
    assertThat(row(connectionId).get("resync_requested_at")).isNotNull();
    assertThat(row(connectionId).get("last_synced_at")).isNull();
  }

  @ParameterizedTest
  @ValueSource(strings = {"member", "outsider"})
  @DisplayName("권한이 부족하면 재동기화 요청을 거부하고 데이터를 유지한다")
  void rejectsInsufficientRoleWithoutWriting(String role) throws Exception {
    UUID workspaceId = workspace();
    UUID caller = user();
    if (!role.equals("outsider")) {
      member(workspaceId, caller, role);
    }
    member(workspace(), caller, "owner");
    UUID connectionId = connection(workspaceId, "ACTIVE");
    Map<String, Object> before = row(connectionId);
    mockMvc
        .perform(request(connectionId, caller))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("AUTH_FORBIDDEN"))
        .andExpect(jsonPath("$.error.details.workspace_id").value(workspaceId.toString()))
        .andExpect(jsonPath("$.error.details.required_role").value("admin"));
    assertThat(row(connectionId)).isEqualTo(before);
  }

  @Test
  @DisplayName("존재하지 않는 연결과 잘못된 식별자의 재동기화 요청을 거부한다")
  void rejectsMissingConnectionAndInvalidId() throws Exception {
    UUID caller = user();
    UUID missingId = UUID.randomUUID();
    mockMvc
        .perform(request(missingId, caller))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("SOURCE_CONNECTION_NOT_FOUND"))
        .andExpect(jsonPath("$.error.details.source_connection_id").value(missingId.toString()))
        .andExpect(jsonPath("$.error.details.length()").value(1));
    mockMvc
        .perform(
            post("/api/source-connections/invalid/resync")
                .header("Authorization", "Bearer " + accessTokens.issueAccessToken(caller))
                .header("API-Version", "1"))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("미인증 재동기화 요청을 거부하고 데이터를 유지한다")
  void rejectsUnauthenticatedRequestWithoutWriting() throws Exception {
    UUID connectionId = connection(workspace(), "ACTIVE");
    Map<String, Object> before = row(connectionId);
    mockMvc
        .perform(
            post("/api/source-connections/{id}/resync", connectionId).header("API-Version", "1"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error.code").value("AUTH_UNAUTHORIZED"));
    assertThat(row(connectionId)).isEqualTo(before);
  }

  @Test
  @DisplayName("writer는 워크스페이스가 다르거나 존재하지 않는 연결의 갱신을 거부한다")
  void writerRejectsMismatchedWorkspaceAndMissingConnection() {
    UUID connectionId = connection(workspace(), "ACTIVE");
    Map<String, Object> before = row(connectionId);
    assertThatThrownBy(() -> writer.requestResync(connectionId, UUID.randomUUID()))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue(
            "details", Map.of("source_connection_id", connectionId.toString()));
    assertThat(row(connectionId)).isEqualTo(before);
    UUID missingId = UUID.randomUUID();
    assertThatThrownBy(() -> writer.requestResync(missingId, UUID.randomUUID()))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue(
            "details", Map.of("source_connection_id", missingId.toString()));
  }

  private Instant databaseNow() {
    return jdbcTemplate.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant();
  }

  private void assertOnlyRequestTimesChanged(
      Map<String, Object> before, Map<String, Object> after) {
    before.remove("resync_requested_at");
    before.remove("updated_at");
    assertThat(after).containsAllEntriesOf(before);
  }

  private MockHttpServletRequestBuilder request(UUID connectionId, UUID caller) {
    return post("/api/source-connections/{id}/resync", connectionId)
        .header("Authorization", "Bearer " + accessTokens.issueAccessToken(caller))
        .header("API-Version", "1");
  }

  private UUID user() {
    return userService.findOrCreate(UUID.randomUUID() + "@momens.works", "재동기화 사용자", null).id();
  }

  private UUID workspace() {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO workspaces (id, name, slug) VALUES (?, 'resync', ?)", id, id.toString());
    return id;
  }

  private void member(UUID workspaceId, UUID caller, String role) {
    jdbcTemplate.update(
        "INSERT INTO workspace_members (workspace_id, user_id, role) VALUES (?, ?, ?)",
        workspaceId,
        caller,
        role);
  }

  private UUID connection(UUID workspaceId, String connectionStatus) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        """
        INSERT INTO source_connections
          (id, workspace_id, source_type, status, last_synced_at, resync_requested_at,
           updated_at, disabled_at, captures_read_count, candidates_extracted_count, metadata)
        VALUES (?, ?, 'FIGMA', ?, '2026-01-02T00:00:00Z', '2026-01-01T00:00:00Z',
          '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z', 12, 3, '{"file_keys":["file-1"]}')
        """,
        id,
        workspaceId,
        connectionStatus);
    return id;
  }

  private Map<String, Object> row(UUID connectionId) {
    return jdbcTemplate.queryForMap("SELECT * FROM source_connections WHERE id = ?", connectionId);
  }
}
