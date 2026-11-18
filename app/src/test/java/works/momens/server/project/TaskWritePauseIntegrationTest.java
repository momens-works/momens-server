package works.momens.server.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.ObjectMapper;
import works.momens.server.auth.AccessTokenTestFactory;
import works.momens.server.common.test.AbstractPostgresIntegrationTest;
import works.momens.server.mcp.grant.CreateMcpGrantCommand;
import works.momens.server.mcp.grant.McpGrantWriter;
import works.momens.server.mcp.grant.McpScope;
import works.momens.server.mcp.transport.McpAuthenticationContext;
import works.momens.server.mcp.transport.McpToolExecutor;

@SpringBootTest(properties = "momens.project.task-writes-paused=true")
@AutoConfigureMockMvc
class TaskWritePauseIntegrationTest extends AbstractPostgresIntegrationTest {
  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private AccessTokenTestFactory tokens;
  @Autowired private McpGrantWriter grants;
  @Autowired private McpToolExecutor tools;
  @Autowired private ObjectMapper mapper;

  @Test
  void rejectsTaskWritersAcrossSurfacesAndKeepsReadsAvailable() throws Exception {
    UUID user = UUID.randomUUID();
    UUID workspace = UUID.randomUUID();
    UUID project = UUID.randomUUID();
    UUID task = UUID.randomUUID();
    UUID signal = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO users(id,email,name) VALUES (?,?,?)", user, user + "@example.com", "Owner");
    jdbc.update(
        "INSERT INTO workspaces(id,name,slug) VALUES (?,?,?)",
        workspace,
        "Pause",
        workspace.toString());
    jdbc.update(
        "INSERT INTO workspace_members(workspace_id,user_id,role) VALUES (?,?,'owner')",
        workspace,
        user);
    jdbc.update(
        "INSERT INTO projects(id,workspace_id,owner_id,name,label) VALUES (?,?,?,'Project','PRJ-4230')",
        project,
        workspace,
        user);
    jdbc.update(
        "INSERT INTO tasks(id,workspace_id,project_id,title,label,status,priority) VALUES (?,?,?,'Existing','MOM-4230','todo','medium')",
        task,
        workspace,
        project);
    jdbc.update(
        "INSERT INTO signals(id,workspace_id,project_id,type,title,description) VALUES (?,?,?,'question','Signal','Fixture')",
        signal,
        workspace,
        project);

    String bearer = "Bearer " + tokens.issueAccessToken(user);
    expectPaused(
        post("/api/projects/{project}/tasks", project).content("{\"title\":\"New\"}"), bearer);
    expectPaused(patch("/api/tasks/{task}", task).content("{\"title\":\"Changed\"}"), bearer);
    expectPaused(delete("/api/tasks/{task}", task), bearer);
    expectPaused(post("/api/tasks/{task}/updates", task).content("{\"body\":\"Comment\"}"), bearer);
    expectPaused(
        post("/api/tasks/{task}/source-refs", task)
            .content("{\"source_url\":\"https://example.com/task\"}"),
        bearer);
    expectPaused(
        post("/api/mobile/projects/{project}/tasks", project)
            .content(
                "{\"title\":\"New from mobile\",\"priority\":\"medium\",\"roles\":[\"backend\"]}"),
        bearer);
    expectPaused(
        patch("/api/mobile/tasks/{task}", task)
            .content(
                "{\"title\":\"Changed on mobile\",\"status\":\"todo\",\"priority\":\"medium\",\"roles\":[\"backend\"]}"),
        bearer);
    expectPaused(post("/api/mobile/signals/{signal}/actions/convert-to-task", signal), bearer);

    var scopes = Set.of(McpScope.TASKS_WRITE.value());
    var grant = grants.create(new CreateMcpGrantCommand(user, "pause-client", workspace, scopes));
    var context = new McpAuthenticationContext(grant.id(), user, "pause-client", workspace, scopes);
    var result =
        tools
            .call(
                "create_task",
                mapper.createObjectNode().put("project", "PRJ-4230").put("title", "New from MCP"),
                context)
            .orElseThrow();
    assertThat(result.path("isError").asBoolean()).isTrue();
    assertThat(result.path("content").get(0).path("text").asText())
        .isEqualTo("Task writes are temporarily paused.");

    mvc.perform(api(get("/api/tasks/{task}", task), bearer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.title").value("Existing"));
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM tasks WHERE workspace_id=?", Long.class, workspace))
        .isEqualTo(1L);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE workspace_id=?", Long.class, workspace))
        .isZero();
  }

  private void expectPaused(MockHttpServletRequestBuilder request, String bearer) throws Exception {
    mvc.perform(api(request, bearer))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.error.code").value("TASK_WRITES_PAUSED"));
  }

  private MockHttpServletRequestBuilder api(MockHttpServletRequestBuilder request, String bearer) {
    return request
        .header("API-Version", "1")
        .header("Authorization", bearer)
        .contentType(MediaType.APPLICATION_JSON);
  }
}
