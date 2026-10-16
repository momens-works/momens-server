package works.momens.server.mcp.tools.internal;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import works.momens.server.mcp.grant.McpGrantReader;
import works.momens.server.mcp.grant.McpScope;
import works.momens.server.mcp.transport.McpAuthenticationContext;
import works.momens.server.mcp.transport.McpToolDefinition;
import works.momens.server.project.core.ProjectDetail;
import works.momens.server.project.core.ProjectDetailReader;
import works.momens.server.project.milestone.MilestoneDetail;
import works.momens.server.project.milestone.MilestoneReader;
import works.momens.server.project.task.TaskReader;
import works.momens.server.project.task.TaskSnapshot;
import works.momens.server.project.task.TaskStatus;
import works.momens.server.project.taskupdate.TaskUpdateDetail;
import works.momens.server.project.taskupdate.TaskUpdateReader;
import works.momens.server.user.UserProfile;
import works.momens.server.user.UserService;
import works.momens.server.workspace.membership.WorkspaceMembershipDetail;
import works.momens.server.workspace.membership.WorkspaceMembershipReader;

@Service
class McpReadToolService {
  private static final Map<String, McpScope> SCOPES =
      Map.of(
          "list_projects", McpScope.PROJECTS_READ,
          "list_members", McpScope.MEMBERS_READ,
          "list_milestones", McpScope.MILESTONES_READ,
          "get_task", McpScope.TASKS_READ,
          "list_tasks", McpScope.TASKS_READ,
          "list_tasks_v2", McpScope.TASKS_READ);

  private final ObjectMapper mapper;
  private final McpGrantReader grants;
  private final WorkspaceMembershipReader memberships;
  private final UserService users;
  private final ProjectDetailReader projects;
  private final MilestoneReader milestones;
  private final TaskReader tasks;
  private final TaskUpdateReader updates;
  private final McpTaskLinks links;
  private final List<McpToolDefinition> definitions;

  McpReadToolService(
      ObjectMapper mapper,
      McpGrantReader grants,
      WorkspaceMembershipReader memberships,
      UserService users,
      ProjectDetailReader projects,
      MilestoneReader milestones,
      TaskReader tasks,
      TaskUpdateReader updates,
      McpTaskLinks links)
      throws IOException {
    this.mapper = mapper;
    this.grants = grants;
    this.memberships = memberships;
    this.users = users;
    this.projects = projects;
    this.milestones = milestones;
    this.tasks = tasks;
    this.updates = updates;
    this.links = links;
    try (InputStream input = new ClassPathResource("mcp/read-tools.json").getInputStream()) {
      List<McpToolDefinition> loaded = new ArrayList<>();
      for (JsonNode tool : mapper.readTree(input)) {
        loaded.add(
            new McpToolDefinition(
                tool.get("name").asText(),
                tool.get("description").asText(),
                tool.get("inputSchema")));
      }
      definitions = List.copyOf(loaded);
    }
  }

  public List<McpToolDefinition> list(McpAuthenticationContext context) {
    return definitions.stream()
        .filter(tool -> context.scopes().contains(SCOPES.get(tool.name()).value()))
        .map(
            tool ->
                new McpToolDefinition(
                    tool.name(), tool.description(), tool.inputSchema().deepCopy()))
        .toList();
  }

  public Optional<JsonNode> call(
      String name, JsonNode arguments, McpAuthenticationContext context) {
    McpScope scope = SCOPES.get(name);
    if (scope == null) {
      return Optional.empty();
    }
    if (!isAuthorized(context, scope)) {
      return Optional.of(result("You don't have access to this tool.", true));
    }
    try {
      validateArguments(name, arguments);
      String text =
          switch (name) {
            case "list_projects" -> listProjects(context);
            case "list_members" -> listMembers(context);
            case "list_milestones" -> listMilestones(context, argument(arguments, "project"));
            case "list_tasks" -> listTasks(context, arguments, false);
            case "list_tasks_v2" -> listTasks(context, arguments, true);
            case "get_task" -> getTask(context, argument(arguments, "task"));
            default -> throw new IllegalStateException("Unregistered read tool");
          };
      return Optional.of(result(text, false));
    } catch (McpToolInputException exception) {
      return Optional.of(result(exception.getMessage(), true));
    }
  }

  private boolean isAuthorized(McpAuthenticationContext context, McpScope scope) {
    return context.permits(context.workspaceId(), scope)
        && grants
            .findActive(context.grantId())
            .filter(
                grant ->
                    grant.userId().equals(context.userId())
                        && grant.clientId().equals(context.clientId())
                        && grant.workspaceId().equals(context.workspaceId())
                        && grant.scopes().contains(scope.value()))
            .isPresent()
        && memberships.roleOf(context.workspaceId(), context.userId()).isPresent();
  }

  private void validateArguments(String name, JsonNode arguments) {
    JsonNode schema =
        definitions.stream()
            .filter(tool -> tool.name().equals(name))
            .findFirst()
            .orElseThrow()
            .inputSchema();
    if (!arguments.isObject()) {
      throw new McpToolInputException("arguments must be an object");
    }
    for (Map.Entry<String, JsonNode> field : arguments.properties()) {
      if (!schema.get("properties").has(field.getKey()) || !field.getValue().isTextual()) {
        throw new McpToolInputException(
            "Unknown argument or invalid argument type: " + field.getKey());
      }
    }
    if (name.equals("get_task") && argument(arguments, "task").isEmpty()) {
      throw new McpToolInputException("task is required (its MOM-label or id)");
    }
  }

  private ObjectNode result(String text, boolean error) {
    ObjectNode result = mapper.createObjectNode();
    result.put("resultType", "complete");
    result.putArray("content").addObject().put("type", "text").put("text", text);
    if (error) {
      result.put("isError", true);
    }
    return result;
  }

  private String listProjects(McpAuthenticationContext context) {
    List<ProjectDetail> found = projects.listDetailsByWorkspaceId(context.workspaceId());
    return lines(
        "project",
        "No projects in this workspace yet.",
        found.stream()
            .map(project -> McpReadToolText.projectName(project) + " [" + project.status() + "]")
            .toList());
  }

  private List<Member> findMembers(McpAuthenticationContext context) {
    List<WorkspaceMembershipDetail> found =
        memberships.listMembershipDetails(context.workspaceId());
    Map<UUID, UserProfile> profiles =
        users.getProfiles(found.stream().map(WorkspaceMembershipDetail::userId).toList()).stream()
            .collect(Collectors.toMap(UserProfile::id, Function.identity()));
    return found.stream()
        .filter(member -> profiles.containsKey(member.userId()))
        .map(member -> new Member(profiles.get(member.userId()), member.role()))
        .toList();
  }

  private String listMembers(McpAuthenticationContext context) {
    return lines(
        "member",
        "No members found.",
        findMembers(context).stream()
            .map(
                member ->
                    member.profile().name()
                        + " <"
                        + member.profile().email()
                        + "> ["
                        + member.role()
                        + "]")
            .toList());
  }

  private String listMilestones(McpAuthenticationContext context, String reference) {
    List<ProjectDetail> found = projects.listDetailsByWorkspaceId(context.workspaceId());
    UUID projectId =
        reference.isEmpty() ? null : McpToolReferences.resolveProject(found, reference).id();
    Map<UUID, ProjectDetail> index = projectIndex(found);
    return lines(
        "milestone",
        "No milestones match.",
        milestones.listDetailsByWorkspaceId(context.workspaceId()).stream()
            .filter(milestone -> projectId == null || milestone.projectId().equals(projectId))
            .map(
                milestone -> McpReadToolText.milestone(milestone, index.get(milestone.projectId())))
            .toList());
  }

  private String listTasks(
      McpAuthenticationContext context, JsonNode arguments, boolean versionTwo) {
    String status = argument(arguments, "status").toLowerCase(Locale.ROOT);
    if (versionTwo && arguments.has("status") && TaskStatus.from(status).isEmpty()) {
      throw new McpToolInputException(
          "Invalid status. Use backlog, todo, in_progress, done, or cancelled; omit status for all tasks.");
    }
    List<ProjectDetail> found = projects.listDetailsByWorkspaceId(context.workspaceId());
    String projectRef = argument(arguments, "project");
    UUID projectId =
        projectRef.isEmpty() ? null : McpToolReferences.resolveProject(found, projectRef).id();
    String assigneeRef = argument(arguments, "assignee");
    UUID assigneeId = resolveAssignee(context, assigneeRef);
    boolean unassignedOnly =
        versionTwo
            && Set.of("none", "unassign", "unassigned")
                .contains(assigneeRef.toLowerCase(Locale.ROOT));
    Map<UUID, ProjectDetail> index = projectIndex(found);
    return lines(
        "task",
        "No tasks match.",
        tasks.listSnapshotsByWorkspaceId(context.workspaceId()).stream()
            .filter(task -> projectId == null || projectId.equals(task.projectId()))
            .filter(
                task ->
                    unassignedOnly
                        ? task.assigneeId() == null
                        : assigneeId == null || assigneeId.equals(task.assigneeId()))
            .filter(task -> status.isEmpty() || status.equals(task.status()))
            .map(task -> McpReadToolText.taskLine(task, index.get(task.projectId())))
            .toList());
  }

  private UUID resolveAssignee(McpAuthenticationContext context, String reference) {
    return switch (reference.toLowerCase(Locale.ROOT)) {
      case "", "none", "unassign", "unassigned" -> null;
      case "me" -> context.userId();
      default ->
          McpToolReferences.resolveAssignee(
              findMembers(context).stream().map(Member::profile).toList(), reference);
    };
  }

  private String getTask(McpAuthenticationContext context, String reference) {
    Optional<UUID> id = McpToolReferences.uuid(reference);
    Optional<TaskSnapshot> found =
        id.isPresent()
            ? tasks.findSnapshotInWorkspace(context.workspaceId(), id.get())
            : tasks.findSnapshotByLabel(context.workspaceId(), reference.toUpperCase(Locale.ROOT));
    TaskSnapshot task =
        found.orElseThrow(
            () -> new McpToolInputException("No task in this workspace: " + reference));
    ProjectDetail project =
        projects.listDetailsByWorkspaceId(context.workspaceId()).stream()
            .filter(candidate -> candidate.id().equals(task.projectId()))
            .findFirst()
            .orElseThrow(
                () -> new McpToolInputException("No task in this workspace: " + reference));
    String milestoneName =
        task.milestoneId() == null
            ? ""
            : milestones.listDetailsByWorkspaceId(context.workspaceId()).stream()
                .filter(milestone -> milestone.id().equals(task.milestoneId()))
                .map(MilestoneDetail::name)
                .findFirst()
                .orElse("");
    String assigneeName =
        task.assigneeId() == null
            ? ""
            : findMembers(context).stream()
                .map(Member::profile)
                .filter(profile -> profile.id().equals(task.assigneeId()))
                .map(UserProfile::name)
                .findFirst()
                .orElse("");
    List<TaskUpdateDetail> activities = updates.listByTaskId(task.id());
    List<UUID> authorIds =
        activities.stream()
            .map(TaskUpdateDetail::authorId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
    Map<UUID, String> authorNames =
        authorIds.isEmpty()
            ? Map.of()
            : users.getProfiles(authorIds).stream()
                .filter(profile -> profile.name() != null && !profile.name().isBlank())
                .collect(Collectors.toMap(UserProfile::id, UserProfile::name));
    return McpReadToolText.taskDetail(
        task,
        project,
        milestoneName,
        assigneeName,
        activities,
        authorNames,
        links.taskUrl(context.workspaceId(), task));
  }

  private static Map<UUID, ProjectDetail> projectIndex(List<ProjectDetail> found) {
    return found.stream().collect(Collectors.toMap(ProjectDetail::id, Function.identity()));
  }

  private static String argument(JsonNode arguments, String name) {
    return arguments.path(name).asText("").strip();
  }

  private static String lines(String kind, String empty, List<String> lines) {
    return lines.isEmpty()
        ? empty
        : lines.size() + " " + kind + "(s):\n- " + String.join("\n- ", lines);
  }

  private record Member(UserProfile profile, String role) {}
}
