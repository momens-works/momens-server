package works.momens.server.mcp.tools.internal;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
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
          "list_task_updates", McpScope.TASKS_READ,
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
  private final List<McpToolDefinition> definitions;

  McpReadToolService(
      ObjectMapper mapper,
      McpGrantReader grants,
      WorkspaceMembershipReader memberships,
      UserService users,
      ProjectDetailReader projects,
      MilestoneReader milestones,
      TaskReader tasks,
      TaskUpdateReader updates)
      throws IOException {
    this.mapper = mapper;
    this.grants = grants;
    this.memberships = memberships;
    this.users = users;
    this.projects = projects;
    this.milestones = milestones;
    this.tasks = tasks;
    this.updates = updates;
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
      McpReadPage page = McpReadPage.parse(name, arguments, context);
      String text =
          switch (name) {
            case "list_projects" -> listProjects(context, page);
            case "list_members" -> listMembers(context, page);
            case "list_milestones" -> listMilestones(context, argument(arguments, "project"), page);
            case "list_tasks" -> listTasks(context, arguments, false, page);
            case "list_tasks_v2" -> listTasks(context, arguments, true, page);
            case "get_task" -> getTask(context, argument(arguments, "task"));
            case "list_task_updates" -> listTaskUpdates(context, argument(arguments, "task"), page);
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
      if (!schema.get("properties").has(field.getKey())
          || (!field.getKey().equals("limit") && !field.getValue().isTextual())) {
        throw new McpToolInputException(
            "Unknown argument or invalid argument type: " + field.getKey());
      }
    }
    if ((name.equals("get_task") || name.equals("list_task_updates"))
        && argument(arguments, "task").isEmpty()) {
      throw new McpToolInputException("task is required (its MOM-label or id)");
    }
  }

  private ObjectNode result(String text, boolean error) {
    ObjectNode result = mapper.createObjectNode();
    result.put("resultType", "complete");
    result
        .putArray("content")
        .addObject()
        .put("type", "text")
        .put("text", McpReadPage.clip(text, 60000));
    if (error) {
      result.put("isError", true);
    }
    return result;
  }

  private String listProjects(McpAuthenticationContext context, McpReadPage page) {
    List<ProjectDetail> found =
        projects.listPage(context.workspaceId(), page.time(), page.id(), page.limit() + 1);
    return page.render(
        "project",
        "No projects in this workspace yet.",
        found,
        project -> McpReadToolText.projectName(project) + " [" + project.status() + "]",
        ProjectDetail::createdAt,
        ProjectDetail::id,
        48000);
  }

  private String listMembers(McpAuthenticationContext context, McpReadPage page) {
    List<WorkspaceMembershipDetail> found =
        memberships.listPage(context.workspaceId(), page.time(), page.id(), page.limit() + 1);
    Map<UUID, UserProfile> profiles =
        users.getProfiles(found.stream().map(WorkspaceMembershipDetail::userId).toList()).stream()
            .collect(Collectors.toMap(UserProfile::id, Function.identity()));
    return page.render(
        "member",
        "No members found.",
        found,
        member -> {
          UserProfile profile = profiles.get(member.userId());
          return (profile == null
                  ? member.userId().toString()
                  : profile.name() + " <" + profile.email() + ">")
              + " ["
              + member.role()
              + "]";
        },
        WorkspaceMembershipDetail::createdAt,
        WorkspaceMembershipDetail::userId,
        48000);
  }

  private String listMilestones(
      McpAuthenticationContext context, String reference, McpReadPage page) {
    UUID projectId = resolveProject(context, reference);
    List<MilestoneDetail> found =
        milestones.listPage(
            context.workspaceId(), projectId, page.time(), page.id(), page.limit() + 1);
    Map<UUID, ProjectDetail> index =
        projectIndex(
            projects.listByIds(
                context.workspaceId(),
                found.stream().map(MilestoneDetail::projectId).distinct().toList()));
    return page.render(
        "milestone",
        "No milestones match.",
        found,
        milestone -> McpReadToolText.milestone(milestone, index.get(milestone.projectId())),
        MilestoneDetail::createdAt,
        MilestoneDetail::id,
        48000);
  }

  private String listTasks(
      McpAuthenticationContext context, JsonNode arguments, boolean versionTwo, McpReadPage page) {
    String status = argument(arguments, "status").toLowerCase(Locale.ROOT);
    if (versionTwo && arguments.has("status") && TaskStatus.from(status).isEmpty()) {
      throw new McpToolInputException(
          "Invalid status. Use backlog, todo, in_progress, done, or cancelled; omit status for all tasks.");
    }
    String projectRef = argument(arguments, "project");
    UUID projectId = resolveProject(context, projectRef);
    String assigneeRef = argument(arguments, "assignee");
    UUID assigneeId = resolveAssignee(context, assigneeRef);
    boolean unassignedOnly =
        versionTwo
            && Set.of("none", "unassign", "unassigned")
                .contains(assigneeRef.toLowerCase(Locale.ROOT));
    List<TaskSnapshot> found =
        tasks.listPage(
            context.workspaceId(),
            projectId,
            assigneeId,
            unassignedOnly,
            status.isEmpty() ? null : status,
            page.time(),
            page.id(),
            page.limit() + 1);
    Map<UUID, ProjectDetail> index =
        projectIndex(
            projects.listByIds(
                context.workspaceId(),
                found.stream().map(TaskSnapshot::projectId).distinct().toList()));
    return page.render(
        "task",
        "No tasks match.",
        found,
        task -> McpReadToolText.taskLine(task, index.get(task.projectId())),
        TaskSnapshot::createdAt,
        TaskSnapshot::id,
        48000);
  }

  private UUID resolveProject(McpAuthenticationContext context, String reference) {
    return reference.isEmpty()
        ? null
        : McpToolReferences.resolveProject(
                projects.findReferenceCandidates(
                    context.workspaceId(),
                    McpToolReferences.uuid(reference).orElse(null),
                    reference),
                reference)
            .id();
  }

  private UUID resolveAssignee(McpAuthenticationContext context, String reference) {
    switch (reference.toLowerCase(Locale.ROOT)) {
      case "", "none", "unassign", "unassigned":
        return null;
      case "me":
        return context.userId();
      default:
        break;
    }
    Optional<UUID> id = McpToolReferences.uuid(reference);
    if (id.isPresent()) {
      if (memberships.roleOf(context.workspaceId(), id.get()).isEmpty()) {
        throw new McpToolInputException("No member with this id in this workspace");
      }
      return id.get();
    }
    // 사용자 정보는 user 공개 계약으로만 읽습니다. 후보는 종류별 두 개만 유지합니다.
    List<UserProfile> candidates = new ArrayList<>();
    Instant after = null;
    UUID afterId = null;
    int exact = 0;
    int partial = 0;
    while (true) {
      List<WorkspaceMembershipDetail> batch =
          memberships.listPage(context.workspaceId(), after, afterId, 100);
      if (batch.isEmpty()) {
        break;
      }
      for (UserProfile profile :
          users.getProfiles(batch.stream().map(WorkspaceMembershipDetail::userId).toList())) {
        if (profile.email().strip().equalsIgnoreCase(reference)) {
          return profile.id();
        }
        if (profile.name().strip().equalsIgnoreCase(reference)) {
          if (exact++ < 2) {
            candidates.add(profile);
          }
        } else if (profile
            .name()
            .strip()
            .toLowerCase(Locale.ROOT)
            .contains(reference.toLowerCase(Locale.ROOT))) {
          if (partial++ < 2) {
            candidates.add(profile);
          }
        }
      }
      if (batch.size() < 100) {
        break;
      }
      WorkspaceMembershipDetail last = batch.getLast();
      after = last.createdAt();
      afterId = last.userId();
    }
    return McpToolReferences.resolveAssignee(candidates, reference);
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
        projects.listByIds(context.workspaceId(), List.of(task.projectId())).stream()
            .filter(candidate -> candidate.id().equals(task.projectId()))
            .findFirst()
            .orElseThrow(
                () -> new McpToolInputException("No task in this workspace: " + reference));
    String milestoneName =
        task.milestoneId() == null
            ? ""
            : milestones.listByIds(context.workspaceId(), List.of(task.milestoneId())).stream()
                .filter(milestone -> milestone.id().equals(task.milestoneId()))
                .map(MilestoneDetail::name)
                .findFirst()
                .orElse("");
    String assigneeName =
        task.assigneeId() == null
                || memberships.roleOf(context.workspaceId(), task.assigneeId()).isEmpty()
            ? ""
            : users.getProfiles(List.of(task.assigneeId())).stream()
                .filter(profile -> profile.id().equals(task.assigneeId()))
                .map(UserProfile::name)
                .findFirst()
                .orElse("");
    List<TaskUpdateDetail> activities = updates.listPage(task.id(), null, null, 11);
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
    String detail =
        McpReadPage.clip(
            McpReadToolText.taskDetail(task, project, milestoneName, assigneeName), 12000);
    ObjectNode args = mapper.createObjectNode().put("task", task.id().toString()).put("limit", 10);
    McpReadPage page = McpReadPage.parse("list_task_updates", args, context);
    return detail
        + "\n\n"
        + activityPage(task, activities, authorNames, page)
        + "\nTo read activities, call list_task_updates with task: "
        + task.id();
  }

  private String listTaskUpdates(
      McpAuthenticationContext context, String reference, McpReadPage page) {
    TaskSnapshot task =
        McpToolReferences.uuid(reference)
            .flatMap(id -> tasks.findSnapshotInWorkspace(context.workspaceId(), id))
            .or(
                () ->
                    tasks.findSnapshotByLabel(
                        context.workspaceId(), reference.toUpperCase(Locale.ROOT)))
            .orElseThrow(
                () -> new McpToolInputException("No task in this workspace: " + reference));
    if (projects.listByIds(context.workspaceId(), List.of(task.projectId())).stream()
        .noneMatch(project -> project.id().equals(task.projectId()))) {
      throw new McpToolInputException("No task in this workspace: " + reference);
    }
    List<TaskUpdateDetail> found =
        updates.listPage(task.id(), page.time(), page.id(), page.limit() + 1);
    List<UUID> authorIds =
        found.stream().map(TaskUpdateDetail::authorId).filter(Objects::nonNull).distinct().toList();
    Map<UUID, String> names =
        users.getProfiles(authorIds).stream()
            .filter(profile -> profile.name() != null && !profile.name().isBlank())
            .collect(Collectors.toMap(UserProfile::id, UserProfile::name));
    return activityPage(task, found, names, page);
  }

  private String activityPage(
      TaskSnapshot task, List<TaskUpdateDetail> found, Map<UUID, String> names, McpReadPage page) {
    return "Total activities: "
        + updates.countByTaskId(task.id())
        + "\n"
        + page.render(
            "activity",
            "No activities.",
            found,
            update -> McpReadToolText.activity(update, names),
            TaskUpdateDetail::createdAt,
            TaskUpdateDetail::id,
            32000);
  }

  private static Map<UUID, ProjectDetail> projectIndex(List<ProjectDetail> found) {
    return found.stream().collect(Collectors.toMap(ProjectDetail::id, Function.identity()));
  }

  private static String argument(JsonNode arguments, String name) {
    return arguments.path(name).asText("").strip();
  }
}
