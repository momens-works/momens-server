package works.momens.server.support.security;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManagerFactory;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import works.momens.server.common.test.AbstractPostgresIntegrationTest;
import works.momens.server.mcp.grant.CreateMcpGrantCommand;
import works.momens.server.mcp.grant.McpGrantDetail;
import works.momens.server.mcp.grant.McpGrantWriter;
import works.momens.server.mcp.grant.McpScope;
import works.momens.server.mcp.transport.McpAuthenticationContext;
import works.momens.server.mcp.transport.McpToolExecutor;

@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@Transactional
class McpReadPaginationIntegrationTest extends AbstractPostgresIntegrationTest {
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper mapper;
  @Autowired McpToolExecutor tools;
  @Autowired EntityManagerFactory entityManagerFactory;
  @Autowired McpGrantWriter grants;
  private final UUID workspaceId = UUID.randomUUID();
  private final UUID userId = UUID.randomUUID();
  private final UUID projectId = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
  private McpAuthenticationContext context;

  @BeforeEach
  void seed() {
    context =
        new McpAuthenticationContext(
            UUID.randomUUID(),
            userId,
            "client",
            workspaceId,
            Arrays.stream(McpScope.values()).map(McpScope::value).collect(Collectors.toSet()));
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
    McpGrantDetail grant =
        grants.create(new CreateMcpGrantCommand(userId, "client", workspaceId, context.scopes()));
    context =
        new McpAuthenticationContext(grant.id(), userId, "client", workspaceId, context.scopes());
    jdbc.update(
        "INSERT INTO projects(id,workspace_id,name,owner_id,label) VALUES (?,?,'Main',?,'PRJ-1004')",
        projectId,
        workspaceId,
        userId);
  }

  @Test
  void taskPagesPreserveUuidTiesAndPushFiltersIntoSql() {
    List<String> expected = new ArrayList<>();
    for (int i = 0; i < 125; i++) {
      UUID id = UUID.randomUUID();
      String label = "MOM-" + (2000 + i);
      expected.add(label);
      task(id, label, "todo", null);
    }
    UUID assigned = UUID.randomUUID();
    task(assigned, "MOM-3000", "todo", userId);
    task(UUID.randomUUID(), "MOM-3001", "done", null);
    UUID deleted = UUID.randomUUID();
    task(deleted, "MOM-3002", "todo", null);
    jdbc.update("UPDATE tasks SET deleted_at=NOW() WHERE id=?", deleted);
    var args =
        mapper
            .createObjectNode()
            .put("project", "PRJ-1004")
            .put("status", " TODO ")
            .put("assignee", "none");
    var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    statistics.clear();
    String first = text(call("list_tasks_v2", args));
    assertThat(first).startsWith("20 task(s):").doesNotContain("MOM-3000", "MOM-3001", "MOM-3002");
    assertThat(statistics.getEntityLoadCount()).isLessThan(30);
    List<String> lines = allLines("list_tasks_v2", args);
    assertThat(lines).hasSize(125);
    assertThat(new HashSet<>(lines)).hasSize(125);
    assertThat(lines.stream().map(line -> line.substring(2, line.indexOf(" · "))))
        .containsExactlyInAnyOrderElementsOf(expected);
    assertThat(text(call("list_tasks", mapper.createObjectNode().put("assignee", "me"))))
        .contains("MOM-3000")
        .doesNotContain("MOM-2000");
    assertThat(text(call("list_tasks_v2", mapper.createObjectNode().put("status", "cancelled"))))
        .isEqualTo("No tasks match.");
  }

  @Test
  void pagesAllOtherListsAndLimitsActivityBodies() {
    for (int i = 0; i < 25; i++) {
      UUID id = UUID.randomUUID();
      jdbc.update(
          "INSERT INTO projects(id,workspace_id,name,owner_id,created_at) VALUES (?,?,?,?,'2026-10-01')",
          id,
          workspaceId,
          "Project " + i,
          userId);
      jdbc.update(
          "INSERT INTO milestones(id,project_id,name,created_at) VALUES (?,?,?,'2026-10-01')",
          UUID.randomUUID(),
          projectId,
          "Milestone " + i);
      UUID member = UUID.randomUUID();
      jdbc.update(
          "INSERT INTO users(id,email,name) VALUES (?,?,?)",
          member,
          member + "@example.com",
          "Member " + i);
      jdbc.update(
          "INSERT INTO workspace_members(workspace_id,user_id,role,created_at) VALUES (?,?,'member','2026-10-01')",
          workspaceId,
          member);
    }
    for (String tool : List.of("list_projects", "list_milestones", "list_members")) {
      List<String> lines = allLines(tool, mapper.createObjectNode().put("limit", 7));
      assertThat(lines).hasSize(tool.equals("list_milestones") ? 25 : 26);
      assertThat(new HashSet<>(lines)).hasSameSizeAs(lines);
    }
    UUID taskId = UUID.randomUUID();
    task(taskId, "MOM-4000", "todo", null);
    for (int i = 0; i < 25; i++) {
      jdbc.update(
          "INSERT INTO task_updates(id,workspace_id,project_id,task_id,author_id,body,kind,created_at) VALUES (?,?,?,?,?,?,'comment','2026-10-01')",
          UUID.randomUUID(),
          workspaceId,
          projectId,
          taskId,
          userId,
          "Activity " + i + " " + "😀한글".repeat(1500));
    }
    String detail = text(call("get_task", mapper.createObjectNode().put("task", "MOM-4000")));
    assertThat(detail).contains("Total activities: 25", "[truncated]", "list_task_updates");
    assertThat(detail.getBytes(StandardCharsets.UTF_8).length).isLessThan(60000);
    List<String> activities =
        allLines(
            "list_task_updates",
            mapper.createObjectNode().put("task", taskId.toString()).put("limit", 100));
    assertThat(activities).hasSize(25);
    assertThat(new HashSet<>(activities)).hasSize(25);
    String cursor = cursor(detail);
    assertThat(cursor).isNotNull();
    assertThat(
            call(
                    "list_task_updates",
                    mapper.createObjectNode().put("task", taskId.toString()).put("cursor", cursor))
                .path("isError")
                .asBoolean())
        .isFalse();
    jdbc.update("UPDATE projects SET deleted_at=NOW() WHERE id=?", projectId);
    assertThat(
            call("list_task_updates", mapper.createObjectNode().put("task", taskId.toString()))
                .path("isError")
                .asBoolean())
        .isTrue();
  }

  @Test
  void lookupCandidatesHonorExactNamesAndWorkspaceIsolation() {
    UUID otherProject = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO projects(id,workspace_id,name,owner_id) VALUES (?,?,'Main extended',?)",
        otherProject,
        workspaceId,
        userId);
    task(UUID.randomUUID(), "MOM-5000", "todo", null);
    assertThat(text(call("list_tasks", mapper.createObjectNode().put("project", "Main"))))
        .contains("MOM-5000");
    assertThat(
            call("list_tasks", mapper.createObjectNode().put("project", "Mai"))
                .path("isError")
                .asBoolean())
        .isTrue();
    assertThat(
            call(
                    "list_tasks",
                    mapper.createObjectNode().put("project", UUID.randomUUID().toString()))
                .path("isError")
                .asBoolean())
        .isTrue();
    McpAuthenticationContext other =
        new McpAuthenticationContext(
            context.grantId(), userId, "client", UUID.randomUUID(), context.scopes());
    assertThat(
            tools
                .call("list_tasks", mapper.createObjectNode(), other)
                .orElseThrow()
                .path("isError")
                .asBoolean())
        .isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {"\tMain\t", "\u2003Main\u3000", "Ma\u0131n"})
  void projectNameMatchingPreservesJavaWhitespaceAndCaseAcrossPages(String exactName) {
    jdbc.update("UPDATE projects SET name=? WHERE id=?", exactName, projectId);
    task(UUID.randomUUID(), "MOM-5001", "todo", null);
    jdbc.update(
        "INSERT INTO milestones(id,project_id,name) VALUES (?,?,'Matched milestone')",
        UUID.randomUUID(),
        projectId);
    // 정확 일치를 UUID 순서의 마지막 배치에 두어 후보 제한 전 판정과 배치 경계를 함께 검증합니다.
    for (int i = 0; i < 105; i++) {
      jdbc.update(
          "INSERT INTO projects(id,workspace_id,name,owner_id) VALUES (?,?,?,?)",
          new UUID(0L, i + 1),
          workspaceId,
          "Main extended " + i,
          userId);
    }
    for (String tool : List.of("list_tasks", "list_tasks_v2", "list_milestones")) {
      assertThat(text(call(tool, mapper.createObjectNode().put("project", "Main"))))
          .contains(tool.equals("list_milestones") ? "Matched milestone" : "MOM-5001");
    }
    UUID duplicate = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO projects(id,workspace_id,name,owner_id) VALUES (?,?,'Main',?)",
        duplicate,
        workspaceId,
        userId);
    for (String tool : List.of("list_tasks", "list_tasks_v2", "list_milestones")) {
      JsonNode result = call(tool, mapper.createObjectNode().put("project", "Main"));
      assertThat(result.path("isError").asBoolean()).isTrue();
      assertThat(result.path("content").get(0).path("text").asText()).contains("several projects");
    }
    assertThat(text(call("list_tasks", mapper.createObjectNode().put("project", "prj-1004"))))
        .contains("MOM-5001");
    assertThat(
            text(
                call("list_tasks", mapper.createObjectNode().put("project", projectId.toString()))))
        .contains("MOM-5001");
    jdbc.update("UPDATE projects SET deleted_at=NOW() WHERE id=?", duplicate);
    assertThat(text(call("list_tasks", mapper.createObjectNode().put("project", "Main"))))
        .contains("MOM-5001");
  }

  private void task(UUID id, String label, String status, UUID assigneeId) {
    jdbc.update(
        "INSERT INTO tasks(id,workspace_id,project_id,title,label,status,priority,assignee_id,created_at) VALUES (?,?,?,'Task',?,?,'medium',?,'2026-10-01 00:00:00.123456+00')",
        id,
        workspaceId,
        projectId,
        label,
        status,
        assigneeId);
  }

  private List<String> allLines(String tool, ObjectNode arguments) {
    List<String> result = new ArrayList<>();
    ObjectNode args = arguments.deepCopy();
    for (int page = 0; page < 100; page++) {
      String text = text(call(tool, args));
      assertThat(text.getBytes(StandardCharsets.UTF_8).length).isLessThan(60000);
      result.addAll(text.lines().filter(line -> line.startsWith("- ")).toList());
      String cursor = cursor(text);
      if (cursor == null) return result;
      args.put("cursor", cursor);
    }
    throw new AssertionError("Pagination did not terminate");
  }

  private String cursor(String text) {
    return text.lines()
        .filter(line -> line.contains("cursor: "))
        .map(line -> line.substring(line.indexOf("cursor: ") + 8))
        .findFirst()
        .orElse(null);
  }

  private JsonNode call(String tool, JsonNode args) {
    return tools.call(tool, args, context).orElseThrow();
  }

  private String text(JsonNode result) {
    assertThat(result.path("isError").asBoolean()).isFalse();
    return result.path("content").get(0).path("text").asText();
  }
}
