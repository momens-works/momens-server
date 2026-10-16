package works.momens.server.support.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.ObjectMapper;
import works.momens.server.auth.AccessTokenTestFactory;
import works.momens.server.common.test.AbstractPostgresIntegrationTest;
import works.momens.server.project.milestone.MilestoneReader;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "momens.mcp.oauth.consent-uri=https://app.example.com/oauth/authorize")
@AutoConfigureMockMvc
@DisplayName("MCP OAuth 인증 경계와 도구 호출 통합 테스트")
class McpOAuthSecurityIntegrationTest extends AbstractPostgresIntegrationTest {
  @Value("${local.server.port}")
  int port;

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired AccessTokenTestFactory accessTokens;
  @MockitoSpyBean MilestoneReader milestones;

  @Test
  @DisplayName("삭제가 완료된 클라이언트의 인가 요청은 400 invalid_request를 반환한다")
  void deletedClientAuthorizationRetainsExistingErrorContract() throws Exception {
    String registered =
        mvc.perform(
                post("/api/oauth2/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        mapper.writeValueAsString(
                            Map.of(
                                "client_name",
                                "deleted client test",
                                "redirect_uris",
                                List.of("http://localhost:3000/callback")))))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String clientId = mapper.readTree(registered).get("client_id").stringValue();
    assertThat(jdbc.update("DELETE FROM oauth2_registered_client WHERE client_id = ?", clientId))
        .isEqualTo(1);

    mvc.perform(
            get("/api/oauth2/authorize")
                .queryParam("response_type", "code")
                .queryParam("client_id", clientId)
                .queryParam("redirect_uri", "http://localhost:3000/callback")
                .queryParam("code_challenge_method", "S256")
                .queryParam("code_challenge", "ZtNPunH49FD35FWYhT5Tv8I7vRKQJ8uxMaL0_9eHjNA")
                .queryParam("resource", "https://api.momens.works/api/mcp"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("invalid_request"));
  }

  @Test
  @DisplayName("사용자 쿠키로 동의하고 MCP 토큰으로 도구를 호출하며 사용자 API 인증과 분리한다")
  void consentUsesTheExistingUserCookieAndMcpTokensCannotAuthenticateUserApis() throws Exception {
    UUID userId = UUID.randomUUID();
    UUID workspaceId = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO users (id, email, name) VALUES (?, ?, 'OAuth user')",
        userId,
        userId + "@example.com");
    jdbc.update(
        "INSERT INTO workspaces (id, name, slug) VALUES (?, 'OAuth workspace', ?)",
        workspaceId,
        workspaceId.toString());
    jdbc.update(
        "INSERT INTO workspace_members (workspace_id, user_id, role) VALUES (?, ?, 'member')",
        workspaceId,
        userId);
    String registered =
        mvc.perform(
                post("/api/oauth2/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        mapper.writeValueAsString(
                            Map.of(
                                "client_name",
                                "security test",
                                "redirect_uris",
                                List.of("http://localhost:3000/callback")))))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String clientId = mapper.readTree(registered).get("client_id").stringValue();
    String consent =
        mvc.perform(
                get("/api/oauth2/authorize")
                    .queryParam("response_type", "code")
                    .queryParam("client_id", clientId)
                    .queryParam("redirect_uri", "http://localhost:3000/callback")
                    .queryParam("code_challenge_method", "S256")
                    .queryParam("code_challenge", "ZtNPunH49FD35FWYhT5Tv8I7vRKQJ8uxMaL0_9eHjNA")
                    .queryParam("resource", "https://api.momens.works/api/mcp"))
            .andExpect(status().isFound())
            .andReturn()
            .getResponse()
            .getRedirectedUrl();
    String interaction =
        UriComponentsBuilder.fromUriString(consent)
            .build()
            .getQueryParams()
            .getFirst("interaction");
    String path = "/api/oauth/interactions/" + interaction;
    mvc.perform(get(path))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error.code").value("AUTH_UNAUTHORIZED"));
    Cookie cookie = new Cookie("access_token", accessTokens.issueAccessToken(userId));
    mvc.perform(get(path).cookie(cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.interaction.workspaces[0].id").value(workspaceId.toString()));
    // Production uses the existing SameSite-cookie policy and CORS filter, without a CSRF token.
    for (String origin : List.of("https://evil.example", "https://untrusted.momens.works")) {
      mvc.perform(
              post(path + "/approve")
                  .cookie(cookie)
                  .header("Origin", origin)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(mapper.writeValueAsString(Map.of("workspace_id", workspaceId))))
          .andExpect(status().isForbidden());
      mvc.perform(post(path + "/deny").cookie(cookie).header("Origin", origin))
          .andExpect(status().isForbidden());
    }
    mvc.perform(get(path).cookie(cookie)).andExpect(status().isOk());
    String approval =
        mvc.perform(
                post(path + "/approve")
                    .header("Origin", "http://localhost:3000")
                    .cookie(cookie)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(Map.of("workspace_id", workspaceId))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String code =
        UriComponentsBuilder.fromUriString(
                mapper.readTree(approval).get("redirect_to").stringValue())
            .build()
            .getQueryParams()
            .getFirst("code");
    String pair =
        mvc.perform(
                post("/api/oauth2/token")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .param("grant_type", "authorization_code")
                    .param("client_id", clientId)
                    .param("code", code)
                    .param("code_verifier", "a".repeat(43))
                    .param("redirect_uri", "http://localhost:3000/callback")
                    .param("resource", "https://api.momens.works/api/mcp"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String token = mapper.readTree(pair).get("access_token").stringValue();
    assertThat(mapper.readTree(pair).get("refresh_token").stringValue()).isNotBlank();
    UUID projectId = UUID.randomUUID();
    UUID taskId = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO projects (id, workspace_id, name, owner_id, label) VALUES (?, ?, 'MCP project', ?, 'PRJ-0991')",
        projectId,
        workspaceId,
        userId);
    jdbc.update(
        "INSERT INTO tasks (id, workspace_id, project_id, title, status, priority, label) VALUES (?, ?, ?, 'Read tools', 'todo', 'medium', 'MOM-0991')",
        taskId,
        workspaceId,
        projectId);
    mvc.perform(mcpRequest(token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.result.tools.length()").value(13));
    mvc.perform(toolRequest(token, "list_projects", Map.of()))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.result.content[0].text")
                .value("1 project(s):\n- PRJ-0991 MCP project [active]"));
    mvc.perform(toolRequest(token, "list_members", Map.of()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.result.content[0].text").value(containsString("OAuth user")));
    mvc.perform(toolRequest(token, "list_milestones", Map.of()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.result.content[0].text").value("No milestones match."));
    mvc.perform(toolRequest(token, "list_tasks", Map.of("project", "PRJ-0991")))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.result.content[0].text")
                .value("1 task(s):\n- MOM-0991 · Read tools [todo] — PRJ-0991 MCP project"));
    mvc.perform(toolRequest(token, "list_tasks_v2", Map.of("assignee", "none", "status", "todo")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.result.isError").doesNotExist())
        .andExpect(jsonPath("$.result.content[0].text").value(containsString("MOM-0991")));
    mvc.perform(toolRequest(token, "list_tasks_v2", Map.of("status", "unknown")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.result.isError").value(true));
    mvc.perform(toolRequest(token, "get_task", Map.of("task", "mom-0991")))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.result.content[0].text")
                .value(
                    "MOM-0991 · Read tools\nstatus: todo · priority: medium · project: PRJ-0991 MCP project\nurl: http://localhost:5173/"
                        + workspaceId
                        + "/projects/"
                        + projectId
                        + "?tab=tasks&task=MOM-0991\n\nTotal activities: 0\nNo activities.\nTo read activities, call list_task_updates with task: "
                        + taskId));
    mvc.perform(toolRequest(token, "get_task", Map.of("task", taskId.toString())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.result.isError").doesNotExist());

    for (int i = 0; i < 12; i++) {
      jdbc.update(
          "INSERT INTO task_updates(id,workspace_id,project_id,task_id,body,kind,created_at) VALUES (?,?,?,?,?,'comment','2026-10-01')",
          UUID.randomUUID(),
          workspaceId,
          projectId,
          taskId,
          "Page activity " + i);
    }
    String firstText =
        httpTool(token, "list_task_updates", Map.of("task", taskId.toString(), "limit", 5));
    assertThat(firstText).contains("Total activities: 12", "5 activity(s)");
    String nextCursor = firstText.substring(firstText.lastIndexOf("cursor: ") + 8);
    String secondText =
        httpTool(
            token,
            "list_task_updates",
            Map.of("task", taskId.toString(), "limit", 5, "cursor", nextCursor));
    assertThat(secondText).contains("5 activity(s)");
    assertThat(secondText.lines().filter(line -> line.startsWith("- ")).toList())
        .doesNotContainAnyElementsOf(
            firstText.lines().filter(line -> line.startsWith("- ")).toList());

    exerciseWriteTools(token, workspaceId, projectId, userId);

    jdbc.update("UPDATE projects SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", projectId);
    for (String reference : List.of(taskId.toString(), "mom-0991")) {
      mvc.perform(toolRequest(token, "get_task", Map.of("task", reference)))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.result.isError").value(true))
          .andExpect(
              jsonPath("$.result.content[0].text")
                  .value("No task in this workspace: " + reference));
    }

    mvc.perform(mcpRequest(cookie.getValue())).andExpect(status().isUnauthorized());
    mvc.perform(mcpRequest(null).cookie(cookie)).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/me").header("Authorization", "Bearer " + token))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error.code").value("AUTH_INVALID_TOKEN"));
    mvc.perform(
            post("/api/oauth2/revoke")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("client_id", clientId)
                .param("token", token))
        .andExpect(status().isOk());
    mvc.perform(mcpRequest(token)).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/me").cookie(cookie)).andExpect(status().isOk());
  }

  private void exerciseWriteTools(String token, UUID workspaceId, UUID projectId, UUID userId)
      throws Exception {
    callWrite(
        token,
        "create_milestone",
        Map.of(
            "project",
            "PRJ-0991",
            "name",
            "Write milestone",
            "description",
            "Keep description",
            "summary",
            "Keep summary",
            "target_date",
            "2026-10-01"));
    UUID milestoneId =
        jdbc.queryForObject(
            "SELECT id FROM milestones WHERE project_id = ? AND name = 'Write milestone'",
            UUID.class,
            projectId);
    assertThat(
            jdbc.queryForObject(
                "SELECT owner_user_id FROM milestone_owners WHERE milestone_id = ?",
                UUID.class,
                milestoneId))
        .isEqualTo(userId);
    callWrite(
        token,
        "update_milestone",
        Map.of(
            "milestone",
            milestoneId.toString(),
            "status",
            "active",
            "health_status",
            "on_track",
            "progress",
            40));
    mvc.perform(
            toolRequest(
                token,
                "update_milestone",
                Map.of(
                    "milestone",
                    milestoneId.toString(),
                    "name",
                    "Rejected name",
                    "health_status",
                    "invalid")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.result.isError").value(true));
    assertThat(
            jdbc.queryForObject(
                "SELECT name FROM milestones WHERE id = ?", String.class, milestoneId))
        .isEqualTo("Write milestone");
    int eventsBeforeFailure =
        jdbc.queryForObject(
            "SELECT count(*) FROM outbox_events WHERE workspace_id = ?",
            Integer.class,
            workspaceId);
    AtomicInteger lookups = new AtomicInteger();
    doAnswer(
            invocation -> {
              if (lookups.incrementAndGet() == 2) {
                throw new IllegalStateException("private SQL diagnostic");
              }
              return invocation.callRealMethod();
            })
        .when(milestones)
        .listDetailsByWorkspaceId(workspaceId);
    try {
      mvc.perform(
              toolRequest(
                  token,
                  "create_task",
                  Map.of(
                      "project",
                      "PRJ-0991",
                      "title",
                      "Rollback task",
                      "milestone",
                      milestoneId.toString())))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.error.code").value(-32603))
          .andExpect(jsonPath("$.error.message").value("Internal error"));
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM tasks WHERE project_id = ? AND title = 'Rollback task'",
                  Integer.class,
                  projectId))
          .isZero();
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM outbox_events WHERE workspace_id = ?",
                  Integer.class,
                  workspaceId))
          .isEqualTo(eventsBeforeFailure);
    } finally {
      doCallRealMethod().when(milestones).listDetailsByWorkspaceId(workspaceId);
    }
    Object milestoneUpdatedAt =
        jdbc.queryForObject(
            "SELECT updated_at FROM milestones WHERE id = ?", Object.class, milestoneId);
    callWrite(
        token,
        "update_milestone",
        Map.of(
            "milestone",
            milestoneId.toString(),
            "status",
            "active",
            "description",
            "",
            "summary",
            "",
            "target_date",
            ""));
    assertThat(
            jdbc.queryForObject(
                "SELECT description FROM milestones WHERE id = ?", String.class, milestoneId))
        .isEqualTo("Keep description");
    assertThat(
            jdbc.queryForObject(
                "SELECT updated_at FROM milestones WHERE id = ?", Object.class, milestoneId))
        .isEqualTo(milestoneUpdatedAt);

    String createdText =
        callWrite(
            token,
            "create_task",
            Map.of(
                "project",
                "PRJ-0991",
                "title",
                "Write task",
                "description",
                "Description",
                "due_date",
                "2026-10-02",
                "assignee",
                "me",
                "milestone",
                milestoneId.toString()));
    UUID id =
        jdbc.queryForObject(
            "SELECT id FROM tasks WHERE project_id = ? AND title = 'Write task'",
            UUID.class,
            projectId);
    String label = jdbc.queryForObject("SELECT label FROM tasks WHERE id = ?", String.class, id);
    String taskUrl =
        "http://localhost:5173/"
            + workspaceId
            + "/projects/"
            + projectId
            + "?tab=tasks&task="
            + label;
    assertThat(createdText).endsWith("\nurl: " + taskUrl);
    assertThat(jdbc.queryForObject("SELECT status FROM tasks WHERE id = ?", String.class, id))
        .isEqualTo("backlog");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE aggregate_id = ? AND event_type = 'task.created'",
                Integer.class,
                id.toString()))
        .isEqualTo(1);
    assertThat(
            callWrite(
                token,
                "update_task",
                Map.of("task", label.toLowerCase(), "status", "progress", "priority", "med")))
        .endsWith("\nurl: " + taskUrl);
    assertThat(jdbc.queryForObject("SELECT description FROM tasks WHERE id = ?", String.class, id))
        .isEqualTo("Description");
    callWrite(
        token,
        "update_task",
        Map.of(
            "task",
            id.toString(),
            "description",
            "",
            "due_date",
            "",
            "assignee",
            "none",
            "milestone",
            "none"));
    Map<String, Object> cleared =
        jdbc.queryForMap(
            "SELECT description, due_date, assignee_id, milestone_id FROM tasks WHERE id = ?", id);
    assertThat(cleared.values()).containsOnlyNulls();
    Object updatedAt =
        jdbc.queryForObject("SELECT updated_at FROM tasks WHERE id = ?", Object.class, id);
    callWrite(token, "update_task", Map.of("task", id.toString(), "status", "in_progress"));
    assertThat(jdbc.queryForObject("SELECT updated_at FROM tasks WHERE id = ?", Object.class, id))
        .isEqualTo(updatedAt);
    callWrite(token, "create_comment", Map.of("task", label, "body", " Comment "));
    Map<String, Object> comment =
        jdbc.queryForMap(
            "SELECT workspace_id, project_id, author_id, body, kind FROM task_updates WHERE task_id = ?",
            id);
    assertThat(comment)
        .containsEntry("workspace_id", workspaceId)
        .containsEntry("project_id", projectId)
        .containsEntry("author_id", userId)
        .containsEntry("body", "Comment")
        .containsEntry("kind", "comment");
    // A repeated create is a new mutation; JSON-RPC ids are not idempotency keys.
    callWrite(token, "create_comment", Map.of("task", label, "body", " Comment "));
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM task_updates WHERE task_id = ?", Integer.class, id))
        .isEqualTo(2);
    for (int attempt = 0; attempt < 2; attempt++) {
      callWrite(token, "create_task", Map.of("project", "PRJ-0991", "title", "Repeated task"));
      callWrite(
          token, "create_milestone", Map.of("project", "PRJ-0991", "name", "Repeated milestone"));
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM tasks WHERE project_id = ? AND title = 'Repeated task'",
                Integer.class,
                projectId))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM milestones WHERE project_id = ? AND name = 'Repeated milestone'",
                Integer.class,
                projectId))
        .isEqualTo(2);
    callWrite(token, "delete_milestone", Map.of("milestone", milestoneId.toString()));
    assertThat(
            jdbc.queryForObject(
                "SELECT deleted_at IS NOT NULL FROM milestones WHERE id = ?",
                Boolean.class,
                milestoneId))
        .isTrue();
    mvc.perform(toolRequest(token, "delete_milestone", Map.of("milestone", milestoneId.toString())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.result.isError").value(true));

    UUID otherWorkspace = UUID.randomUUID();
    UUID otherProject = UUID.randomUUID();
    UUID otherTask = UUID.randomUUID();
    UUID otherMilestone = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO workspaces (id, name, slug) VALUES (?, 'Other', ?)",
        otherWorkspace,
        otherWorkspace.toString());
    jdbc.update(
        "INSERT INTO workspace_members (workspace_id, user_id, role) VALUES (?, ?, 'member')",
        otherWorkspace,
        userId);
    jdbc.update(
        "INSERT INTO projects (id, workspace_id, name, owner_id) VALUES (?, ?, 'Other project', ?)",
        otherProject,
        otherWorkspace,
        userId);
    jdbc.update(
        "INSERT INTO tasks (id, workspace_id, project_id, title) VALUES (?, ?, ?, 'Other task')",
        otherTask,
        otherWorkspace,
        otherProject);
    jdbc.update(
        "INSERT INTO milestones (id, project_id, name) VALUES (?, ?, 'Other milestone')",
        otherMilestone,
        otherProject);
    for (String tool : List.of("update_task", "create_comment")) {
      Map<String, String> args =
          tool.equals("update_task")
              ? Map.of("task", otherTask.toString(), "title", "Unauthorized")
              : Map.of("task", otherTask.toString(), "body", "Unauthorized");
      mvc.perform(toolRequest(token, tool, args))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.result.isError").value(true));
    }
    for (String tool : List.of("update_milestone", "delete_milestone")) {
      mvc.perform(toolRequest(token, tool, Map.of("milestone", otherMilestone.toString())))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.result.isError").value(true));
    }
    mvc.perform(
            toolRequest(
                token,
                "create_task",
                Map.of("project", otherProject.toString(), "title", "Unauthorized")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.result.isError").value(true));
    mvc.perform(
            toolRequest(
                token,
                "create_milestone",
                Map.of("project", otherProject.toString(), "name", "Unauthorized")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.result.isError").value(true));
    assertThat(jdbc.queryForObject("SELECT title FROM tasks WHERE id = ?", String.class, otherTask))
        .isEqualTo("Other task");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM task_updates WHERE task_id = ?", Integer.class, otherTask))
        .isZero();
  }

  private String callWrite(String token, String name, Map<String, ?> args) throws Exception {
    String response =
        mvc.perform(toolRequest(token, name, args))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.error").doesNotExist())
            .andExpect(jsonPath("$.result.isError").doesNotExist())
            .andExpect(jsonPath("$.result.content[0].type").value("text"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    return mapper.readTree(response).path("result").path("content").get(0).path("text").asText();
  }

  private String httpTool(String token, String name, Map<String, ?> arguments) throws Exception {
    String body =
        mapper.writeValueAsString(
            Map.of(
                "jsonrpc",
                "2.0",
                "id",
                7,
                "method",
                "tools/call",
                "params",
                Map.of(
                    "name",
                    name,
                    "arguments",
                    arguments,
                    "_meta",
                    Map.of(
                        "io.modelcontextprotocol/protocolVersion",
                        "2026-07-28",
                        "io.modelcontextprotocol/clientCapabilities",
                        Map.of()))));
    try (HttpClient client =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
      HttpResponse<String> response =
          client.send(
              HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/mcp"))
                  .timeout(Duration.ofSeconds(20))
                  .header("Authorization", "Bearer " + token)
                  .header("MCP-Protocol-Version", "2026-07-28")
                  .header("Mcp-Method", "tools/call")
                  .header("Mcp-Name", name)
                  .header("Accept", "application/json, text/event-stream")
                  .header("Content-Type", "application/json")
                  .POST(HttpRequest.BodyPublishers.ofString(body))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertThat(response.statusCode()).isEqualTo(200);
      var result = mapper.readTree(response.body()).path("result");
      assertThat(result.path("isError").asBoolean()).isFalse();
      return result.path("content").get(0).path("text").asText();
    }
  }

  private MockHttpServletRequestBuilder toolRequest(
      String token, String name, Map<String, ?> arguments) {
    return post("/api/mcp")
        .header("Authorization", "Bearer " + token)
        .header("MCP-Protocol-Version", "2026-07-28")
        .header("Mcp-Method", "tools/call")
        .header("Mcp-Name", name)
        .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
        .contentType(MediaType.APPLICATION_JSON)
        .content(
            mapper.writeValueAsString(
                Map.of(
                    "jsonrpc",
                    "2.0",
                    "id",
                    2,
                    "method",
                    "tools/call",
                    "params",
                    Map.of(
                        "name",
                        name,
                        "arguments",
                        arguments,
                        "_meta",
                        Map.of(
                            "io.modelcontextprotocol/protocolVersion",
                            "2026-07-28",
                            "io.modelcontextprotocol/clientCapabilities",
                            Map.of())))));
  }

  private MockHttpServletRequestBuilder mcpRequest(String token) {
    MockHttpServletRequestBuilder request =
        post("/api/mcp")
            .header("MCP-Protocol-Version", "2026-07-28")
            .header("Mcp-Method", "tools/list")
            .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """
            {"jsonrpc":"2.0","id":1,"method":"tools/list","params":{"_meta":{
              "io.modelcontextprotocol/protocolVersion":"2026-07-28",
              "io.modelcontextprotocol/clientCapabilities":{}}}}
            """);
    return token == null ? request : request.header("Authorization", "Bearer " + token);
  }
}
