package works.momens.server.mcp.tools.internal;

import java.time.LocalDate;
import works.momens.server.project.core.ProjectDetail;
import works.momens.server.project.milestone.MilestoneDetail;
import works.momens.server.project.task.TaskSnapshot;

final class McpWriteToolText {
  private McpWriteToolText() {}

  static String label(TaskSnapshot task) {
    return task.label() == null || task.label().isEmpty() ? task.id().toString() : task.label();
  }

  static String createdTask(TaskSnapshot task, ProjectDetail project, String milestone) {
    return "Created "
        + label(task)
        + " · "
        + task.title()
        + "\nproject: "
        + McpReadToolText.projectName(project)
        + " · status: "
        + task.status()
        + " · priority: "
        + task.priority()
        + (milestone.isEmpty() ? "" : " · milestone: " + milestone)
        + (task.dueDate() == null ? "" : " · due: " + task.dueDate())
        + " · version: "
        + task.version();
  }

  static String updatedTask(TaskSnapshot task, String milestone) {
    return "Updated "
        + label(task)
        + " · "
        + task.title()
        + "\nstatus: "
        + task.status()
        + " · priority: "
        + task.priority()
        + (!milestone.isEmpty()
            ? " · milestone: " + milestone
            : task.milestoneId() == null ? " · milestone: none" : "")
        + (task.dueDate() == null ? "" : " · due: " + task.dueDate())
        + " · version: "
        + task.version();
  }

  static String createdMilestone(MilestoneDetail milestone, ProjectDetail project) {
    return "Created milestone "
        + milestone.name()
        + " ("
        + milestone.id()
        + ")."
        + "\nproject: "
        + McpReadToolText.projectName(project)
        + " · status: "
        + milestone.status()
        + " · target: "
        + date(milestone.targetDate())
        + " · version: "
        + milestone.version();
  }

  static String updatedMilestone(MilestoneDetail milestone) {
    return "Updated milestone "
        + milestone.name()
        + " ("
        + milestone.id()
        + ")."
        + "\nstatus: "
        + milestone.status()
        + " · health: "
        + milestone.healthStatus()
        + " · progress: "
        + milestone.progress()
        + "% · target: "
        + date(milestone.targetDate())
        + " · version: "
        + milestone.version();
  }

  private static String date(LocalDate value) {
    return value == null ? "none" : value.toString();
  }
}
