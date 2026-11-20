package works.momens.server.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import works.momens.server.common.test.AbstractPostgresIntegrationTest;
import works.momens.server.mcp.grant.CreateMcpGrantCommand;
import works.momens.server.mcp.grant.McpGrantWriter;
import works.momens.server.mcp.grant.McpScope;
import works.momens.server.mcp.transport.McpAuthenticationContext;
import works.momens.server.mcp.transport.McpToolExecutor;

/**
 * 프로젝트만 소프트 삭제된 데이터에서 MCP 도구가 그 프로젝트의 마일스톤을 조회·변경 대상에서 제외하는지 실제 DB로 검증합니다.
 *
 * <p>마일스톤 조회는 호출자가 넘긴 프로젝트 id 목록만 따르므로, 삭제된 프로젝트를 거르는 책임은 MCP가 호출하는 project core 공개 계약에 있습니다. mock
 * 기반 단위 테스트로는 두 계약이 맞물린 결과를 확인할 수 없어 통합 테스트로 고정합니다.
 */
@SpringBootTest
@Transactional
class McpDeletedProjectMilestoneIntegrationTest extends AbstractPostgresIntegrationTest {
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper mapper;
  @Autowired McpToolExecutor tools;
  @Autowired McpGrantWriter grants;
  @Autowired EntityManager entityManager;
  private final UUID workspaceId = UUID.randomUUID();
  private final UUID userId = UUID.randomUUID();
  private final UUID liveProjectId = UUID.randomUUID();
  private final UUID deletedProjectId = UUID.randomUUID();
  private final UUID liveMilestoneId = UUID.randomUUID();
  private final UUID deletedMilestoneId = UUID.randomUUID();
  private final UUID taskId = UUID.randomUUID();
  private McpAuthenticationContext context;

  @BeforeEach
  void seed() {
    jdbc.update(
        "INSERT INTO users(id,email,name) VALUES (?,?,?)",
        userId,
        userId + "@example.com",
        "Owner");
    jdbc.update(
        "INSERT INTO workspaces(id,name,slug) VALUES (?,?,?)",
        workspaceId,
        "Workspace",
        workspaceId.toString());
    jdbc.update(
        "INSERT INTO workspace_members(workspace_id,user_id,role) VALUES (?,?,'owner')",
        workspaceId,
        userId);
    var scopes = Arrays.stream(McpScope.values()).map(McpScope::value).collect(Collectors.toSet());
    var grant = grants.create(new CreateMcpGrantCommand(userId, "client", workspaceId, scopes));
    context = new McpAuthenticationContext(grant.id(), userId, "client", workspaceId, scopes);
    project(liveProjectId, "Live", "PRJ-1004");
    project(deletedProjectId, "Archived", "PRJ-1005");
    milestone(liveMilestoneId, liveProjectId, "Live milestone");
    milestone(deletedMilestoneId, deletedProjectId, "Archived milestone");
    jdbc.update(
        "INSERT INTO tasks(id,workspace_id,project_id,title,label,status,priority) VALUES (?,?,?,'Task','MOM-6000','todo','medium')",
        taskId,
        workspaceId,
        deletedProjectId);
  }

  @Test
  @DisplayName("list_milestones는 프로젝트를 지정하지 않으면 삭제된 프로젝트의 마일스톤을 제외한다")
  void listMilestonesWithoutProjectExcludesMilestonesOfDeletedProject() {
    assertThat(text(call("list_milestones", args())))
        .contains("Live milestone", "Archived milestone");

    deleteProject();

    assertThat(text(call("list_milestones", args())))
        .contains("Live milestone")
        .doesNotContain("Archived milestone");
  }

  @Test
  @DisplayName("update_milestone·delete_milestone은 삭제된 프로젝트의 마일스톤을 찾을 수 없는 것으로 처리하고 변경하지 않는다")
  void updateAndDeleteMilestoneTreatMilestoneOfDeletedProjectAsNotFound() {
    deleteProject();

    for (String tool : List.of("update_milestone", "delete_milestone")) {
      for (String reference : List.of(deletedMilestoneId.toString(), "Archived milestone")) {
        ObjectNode args = args().put("milestone", reference);
        if (tool.equals("update_milestone")) {
          args.put("name", "Renamed");
        }
        assertThat(error(call(tool, args))).as(tool + " / " + reference).startsWith("No milestone");
      }
    }
    entityManager.flush();
    assertThat(
            jdbc.queryForMap(
                "SELECT name, deleted_at, version FROM milestones WHERE id=?", deletedMilestoneId))
        .containsEntry("name", "Archived milestone")
        .containsEntry("deleted_at", null)
        .containsEntry("version", 0L);
  }

  @Test
  @DisplayName("create_task는 삭제된 프로젝트에 마일스톤을 지정한 작업을 만들지 않는다")
  void createTaskRejectsDeletedProject() {
    deleteProject();

    assertThat(
            error(
                call(
                    "create_task",
                    args()
                        .put("project", "PRJ-1005")
                        .put("title", "Rejected")
                        .put("milestone", deletedMilestoneId.toString()))))
        .startsWith("No project");
    entityManager.flush();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM tasks WHERE title='Rejected'", Long.class))
        .isZero();
  }

  @Test
  @DisplayName("update_task는 삭제된 프로젝트의 작업에 마일스톤을 지정하지 않는다")
  void updateTaskRejectsTaskOfDeletedProject() {
    deleteProject();

    assertThat(
            error(
                call(
                    "update_task",
                    args()
                        .put("task", "MOM-6000")
                        .put("milestone", deletedMilestoneId.toString()))))
        .startsWith("Resource not found");
    entityManager.flush();
    Map<String, Object> task =
        jdbc.queryForMap("SELECT milestone_id, version FROM tasks WHERE id=?", taskId);
    assertThat(task).containsEntry("milestone_id", null).containsEntry("version", 0L);
  }

  private void project(UUID id, String name, String label) {
    jdbc.update(
        "INSERT INTO projects(id,workspace_id,name,owner_id,label) VALUES (?,?,?,?,?)",
        id,
        workspaceId,
        name,
        userId,
        label);
  }

  private void milestone(UUID id, UUID projectId, String name) {
    jdbc.update("INSERT INTO milestones(id,project_id,name) VALUES (?,?,?)", id, projectId, name);
  }

  private void deleteProject() {
    jdbc.update("UPDATE projects SET deleted_at=NOW() WHERE id=?", deletedProjectId);
  }

  private ObjectNode args() {
    return mapper.createObjectNode();
  }

  private JsonNode call(String tool, JsonNode args) {
    return tools.call(tool, args, context).orElseThrow();
  }

  private String text(JsonNode result) {
    assertThat(result.path("isError").asBoolean()).isFalse();
    return result.path("content").get(0).path("text").asText();
  }

  private String error(JsonNode result) {
    assertThat(result.path("isError").asBoolean()).isTrue();
    return result.path("content").get(0).path("text").asText();
  }
}
