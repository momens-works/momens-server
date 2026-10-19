package works.momens.server.mcp.tools.internal;

import static works.momens.server.mcp.tools.internal.McpToolInputs.argument;
import static works.momens.server.mcp.tools.internal.McpToolInputs.date;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import works.momens.server.mcp.transport.McpAuthenticationContext;
import works.momens.server.project.core.ProjectDetail;
import works.momens.server.project.core.ProjectDetailReader;
import works.momens.server.project.milestone.CreateMilestoneCommand;
import works.momens.server.project.milestone.MilestoneDetail;
import works.momens.server.project.milestone.MilestoneHealthStatus;
import works.momens.server.project.milestone.MilestoneReader;
import works.momens.server.project.milestone.MilestoneWriter;
import works.momens.server.project.milestone.UpdateMilestoneCommand;

@Service
@RequiredArgsConstructor
class McpMilestoneToolService {
  private final ProjectDetailReader projects;
  private final MilestoneReader milestones;
  private final MilestoneWriter milestoneWriter;

  String createMilestone(McpAuthenticationContext context, JsonNode args) {
    ProjectDetail project =
        McpToolReferences.resolveProject(
            projects.listDetailsByWorkspaceId(context.workspaceId()), argument(args, "project"));
    String health = argument(args, "health_status");
    MilestoneHealthStatus healthStatus =
        health.isEmpty()
            ? null
            : MilestoneHealthStatus.from(health)
                .orElseThrow(() -> new McpToolInputException("Invalid health_status"));
    MilestoneDetail milestone =
        milestoneWriter.create(
            new CreateMilestoneCommand(
                project.id(),
                context.workspaceId(),
                context.userId(),
                argument(args, "name"),
                argument(args, "description"),
                date(argument(args, "target_date"), "target_date"),
                healthStatus,
                progress(args),
                argument(args, "summary"),
                null,
                List.of()));
    return McpWriteToolText.createdMilestone(milestone, project);
  }

  String updateMilestone(McpAuthenticationContext context, JsonNode args) {
    MilestoneDetail current = findMilestone(context, argument(args, "milestone"));
    MilestoneDetail milestone =
        milestoneWriter.update(
            new UpdateMilestoneCommand(
                current.id(),
                argument(args, "name"),
                argument(args, "description"),
                argument(args, "status"),
                date(argument(args, "target_date"), "target_date"),
                argument(args, "health_status"),
                progress(args),
                argument(args, "summary"),
                McpToolInputs.version(args)));
    return McpWriteToolText.updatedMilestone(milestone);
  }

  String deleteMilestone(McpAuthenticationContext context, String reference, Long version) {
    MilestoneDetail milestone = findMilestone(context, reference);
    milestoneWriter.delete(milestone.id(), version);
    return "Removed milestone " + milestone.name() + " (" + milestone.id() + ").";
  }

  private MilestoneDetail findMilestone(McpAuthenticationContext context, String reference) {
    MilestoneDetail milestone =
        McpToolReferences.resolveMilestone(
            milestones.listDetailsByWorkspaceId(context.workspaceId()), reference);
    McpToolReferences.requireProject(
        projects.listDetailsByWorkspaceId(context.workspaceId()), milestone.projectId());
    return milestone;
  }

  private static Integer progress(JsonNode args) {
    if (!args.has("progress")) {
      return null;
    }
    int value = args.get("progress").intValue();
    if (value < 0 || value > 100) {
      throw new McpToolInputException("progress must be between 0 and 100");
    }
    return value;
  }
}
