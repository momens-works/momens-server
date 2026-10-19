package works.momens.server.mcp.tools.internal;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;
import works.momens.server.project.core.ProjectDetail;
import works.momens.server.project.milestone.MilestoneDetail;
import works.momens.server.project.task.TaskSnapshot;
import works.momens.server.project.taskupdate.TaskUpdateDetail;

final class McpReadToolText {
  private static final DateTimeFormatter COMMENT_TIME =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC);

  private McpReadToolText() {}

  static String projectName(ProjectDetail project) {
    return project.label() == null || project.label().isEmpty()
        ? project.name()
        : project.label() + " " + project.name();
  }

  private static String label(TaskSnapshot task) {
    return task.label() == null || task.label().isEmpty() ? task.id().toString() : task.label();
  }

  static String taskLine(TaskSnapshot task, ProjectDetail project) {
    return label(task)
        + " · "
        + task.title()
        + " ["
        + task.status()
        + "] · version: "
        + task.version()
        + (project == null ? "" : " — " + projectName(project));
  }

  static String milestone(MilestoneDetail milestone, ProjectDetail project) {
    return milestone.name()
        + " ("
        + milestone.id()
        + ") ["
        + milestone.status()
        + "] · health: "
        + milestone.healthStatus()
        + " · progress: "
        + milestone.progress()
        + "% · version: "
        + milestone.version()
        + (project == null ? "" : " — " + projectName(project))
        + (milestone.targetDate() == null ? "" : " · target: " + milestone.targetDate());
  }

  static String taskDetail(
      TaskSnapshot task, ProjectDetail project, String milestone, String assignee, String url) {
    StringBuilder text =
        new StringBuilder(label(task))
            .append(" · ")
            .append(task.title())
            .append("\nstatus: ")
            .append(task.status())
            .append(" · priority: ")
            .append(task.priority())
            .append(" · version: ")
            .append(task.version());
    if (project != null) {
      text.append(" · project: ").append(projectName(project));
    }
    if (!milestone.isEmpty()) {
      text.append(" · milestone: ").append(milestone);
    }
    if (!assignee.isEmpty()) {
      text.append(" · assignee: ").append(assignee);
    }
    if (task.dueDate() != null) {
      text.append(" · due: ").append(task.dueDate());
    }
    text.append("\nurl: ").append(url);
    if (task.description() != null && !task.description().isEmpty()) {
      text.append("\n\n").append(task.description());
    }
    return text.toString();
  }

  static String activity(TaskUpdateDetail update, Map<UUID, String> names) {
    return "["
        + COMMENT_TIME.format(update.createdAt())
        + " UTC] "
        + update.kind()
        + " · "
        + (update.authorId() == null
            ? "Unknown author"
            : names.getOrDefault(update.authorId(), update.authorId().toString()))
        + ": "
        + update.body();
  }
}
