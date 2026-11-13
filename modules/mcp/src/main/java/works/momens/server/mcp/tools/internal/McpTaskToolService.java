package works.momens.server.mcp.tools.internal;

import static works.momens.server.mcp.tools.internal.McpToolInputs.argument;
import static works.momens.server.mcp.tools.internal.McpToolInputs.date;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import works.momens.server.mcp.transport.McpAuthenticationContext;
import works.momens.server.project.core.ProjectDetail;
import works.momens.server.project.core.ProjectDetailReader;
import works.momens.server.project.milestone.MilestoneDetail;
import works.momens.server.project.milestone.MilestoneReader;
import works.momens.server.project.task.CreateTaskCommand;
import works.momens.server.project.task.PatchTaskCommand;
import works.momens.server.project.task.TaskOrigin;
import works.momens.server.project.task.TaskPriority;
import works.momens.server.project.task.TaskReader;
import works.momens.server.project.task.TaskSnapshot;
import works.momens.server.project.task.TaskStatus;
import works.momens.server.project.task.TaskWriter;
import works.momens.server.project.taskupdate.CreateTaskUpdateCommand;
import works.momens.server.project.taskupdate.TaskUpdateKind;
import works.momens.server.project.taskupdate.TaskUpdateWriter;
import works.momens.server.user.UserService;
import works.momens.server.workspace.membership.WorkspaceMembershipReader;

@Service
@RequiredArgsConstructor
class McpTaskToolService {
  private final WorkspaceMembershipReader memberships;
  private final UserService users;
  private final ProjectDetailReader projects;
  private final MilestoneReader milestones;
  private final TaskReader tasks;
  private final TaskWriter taskWriter;
  private final TaskUpdateWriter updates;
  private final McpTaskLinks links;

  String createTask(McpAuthenticationContext context, JsonNode args) {
    ProjectDetail project =
        McpToolReferences.resolveProject(
            projects.listDetailsByWorkspaceId(context.workspaceId()), argument(args, "project"));
    UUID milestoneId = resolveMilestone(project.id(), argument(args, "milestone"));
    TaskSnapshot task =
        taskWriter.create(
            new CreateTaskCommand(
                project.id(),
                context.workspaceId(),
                argument(args, "title"),
                emptyToNull(argument(args, "description")),
                taskStatus(argument(args, "status")),
                null,
                taskPriority(argument(args, "priority")),
                milestoneId,
                resolveAssignee(context, argument(args, "assignee")),
                date(argument(args, "due_date"), "due_date"),
                TaskOrigin.MANUAL,
                null));
    return McpWriteToolText.createdTask(task, project, milestoneName(task))
        + "\nurl: "
        + links.taskUrl(context.workspaceId(), task);
  }

  String updateTask(McpAuthenticationContext context, JsonNode args) {
    TaskSnapshot current = findTask(context, argument(args, "task"));
    String status = argument(args, "status");
    String priority = argument(args, "priority");
    String milestone = argument(args, "milestone");
    String assignee = argument(args, "assignee");
    TaskSnapshot task =
        taskWriter.patch(
            new PatchTaskCommand(
                current.id(),
                argument(args, "title"),
                !argument(args, "title").isEmpty(),
                emptyToNull(argument(args, "description")),
                args.has("description"),
                status.isEmpty() ? null : taskStatus(status),
                !status.isEmpty(),
                priority.isEmpty() ? null : taskPriority(priority),
                !priority.isEmpty(),
                resolveMilestone(current.projectId(), milestone),
                !milestone.isEmpty(),
                resolveAssignee(context, assignee),
                !assignee.isEmpty(),
                date(argument(args, "due_date"), "due_date"),
                args.has("due_date"),
                McpToolInputs.version(args)));
    return McpWriteToolText.updatedTask(task, milestoneName(task))
        + "\nurl: "
        + links.taskUrl(context.workspaceId(), task);
  }

  String createComment(McpAuthenticationContext context, JsonNode args) {
    TaskSnapshot task = findTask(context, argument(args, "task"));
    updates.create(
        new CreateTaskUpdateCommand(
            task.id(),
            context.workspaceId(),
            task.projectId(),
            context.userId(),
            argument(args, "body"),
            TaskUpdateKind.COMMENT,
            null));
    return "Added a comment to " + McpWriteToolText.label(task) + ".";
  }

  private TaskSnapshot findTask(McpAuthenticationContext context, String reference) {
    Optional<UUID> id = McpToolReferences.uuid(reference);
    TaskSnapshot task =
        (id.isPresent()
                ? tasks.findSnapshotInWorkspace(context.workspaceId(), id.get())
                : tasks.findSnapshotByLabel(
                    context.workspaceId(), reference.toUpperCase(Locale.ROOT)))
            .orElseThrow(
                () -> new McpToolInputException("No task in this workspace: " + reference));
    McpToolReferences.requireProject(
        projects.listDetailsByWorkspaceId(context.workspaceId()), task.projectId());
    return task;
  }

  /** projectId는 워크스페이스의 삭제되지 않은 project로 이미 확인된 값입니다. */
  private UUID resolveMilestone(UUID projectId, String reference) {
    return switch (reference.toLowerCase(Locale.ROOT)) {
      case "", "none", "remove", "unassign", "unassigned" -> null;
      default ->
          McpToolReferences.resolveMilestone(
                  milestones.listDetailsByProjectIds(List.of(projectId)), reference)
              .id();
    };
  }

  /** 작업의 마일스톤은 같은 project 소속으로 검증되어 저장됩니다. */
  private String milestoneName(TaskSnapshot task) {
    return task.milestoneId() == null
        ? ""
        : milestones.listByIds(List.of(task.projectId()), List.of(task.milestoneId())).stream()
            .map(MilestoneDetail::name)
            .findFirst()
            .orElse("");
  }

  private UUID resolveAssignee(McpAuthenticationContext context, String reference) {
    return switch (reference.toLowerCase(Locale.ROOT)) {
      case "", "none", "unassign", "unassigned" -> null;
      case "me" -> context.userId();
      default ->
          McpToolReferences.resolveAssignee(
              users.getProfiles(
                  memberships.listMembershipDetails(context.workspaceId()).stream()
                      .map(member -> member.userId())
                      .toList()),
              reference);
    };
  }

  private static TaskStatus taskStatus(String value) {
    String normalized = value.toLowerCase(Locale.ROOT);
    if (normalized.equals("progress") || normalized.equals("in-progress")) {
      normalized = TaskStatus.IN_PROGRESS.value();
    }
    return normalized.isEmpty()
        ? TaskStatus.BACKLOG
        : TaskStatus.from(normalized)
            .orElseThrow(() -> new McpToolInputException("Invalid status"));
  }

  private static TaskPriority taskPriority(String value) {
    String normalized = value.toLowerCase(Locale.ROOT);
    if (normalized.equals("med")) {
      normalized = TaskPriority.MEDIUM.value();
    }
    return normalized.isEmpty()
        ? TaskPriority.MEDIUM
        : TaskPriority.from(normalized)
            .orElseThrow(() -> new McpToolInputException("Invalid priority"));
  }

  private static String emptyToNull(String value) {
    return value.isEmpty() ? null : value;
  }
}
