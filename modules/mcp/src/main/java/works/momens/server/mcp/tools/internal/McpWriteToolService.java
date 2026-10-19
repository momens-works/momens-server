package works.momens.server.mcp.tools.internal;

import static works.momens.server.mcp.tools.internal.McpToolInputs.argument;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import works.momens.server.mcp.grant.McpGrantReader;
import works.momens.server.mcp.grant.McpScope;
import works.momens.server.mcp.transport.McpAuthenticationContext;
import works.momens.server.mcp.transport.McpToolDefinition;
import works.momens.server.workspace.membership.WorkspaceMembershipReader;

@Service
class McpWriteToolService {
  private static final Map<String, McpScope> SCOPES =
      Map.of(
          "create_task", McpScope.TASKS_WRITE,
          "update_task", McpScope.TASKS_WRITE,
          "create_comment", McpScope.TASKS_WRITE,
          "create_milestone", McpScope.MILESTONES_WRITE,
          "update_milestone", McpScope.MILESTONES_WRITE,
          "delete_milestone", McpScope.MILESTONES_WRITE);

  private final ObjectMapper mapper;
  private final McpGrantReader grants;
  private final WorkspaceMembershipReader memberships;
  private final McpTaskToolService taskTools;
  private final McpMilestoneToolService milestoneTools;
  private final List<McpToolDefinition> definitions;

  McpWriteToolService(
      ObjectMapper mapper,
      McpGrantReader grants,
      WorkspaceMembershipReader memberships,
      McpTaskToolService taskTools,
      McpMilestoneToolService milestoneTools)
      throws IOException {
    this.mapper = mapper;
    this.grants = grants;
    this.memberships = memberships;
    this.taskTools = taskTools;
    this.milestoneTools = milestoneTools;
    try (InputStream input = new ClassPathResource("mcp/write-tools.json").getInputStream()) {
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

  @Transactional
  public Optional<JsonNode> call(
      String name, JsonNode arguments, McpAuthenticationContext context) {
    McpScope scope = SCOPES.get(name);
    if (scope == null) {
      return Optional.empty();
    }
    requireAuthorization(context, scope);
    validateArguments(name, arguments);
    String text =
        switch (name) {
          case "create_task" -> taskTools.createTask(context, arguments);
          case "update_task" -> taskTools.updateTask(context, arguments);
          case "create_comment" -> taskTools.createComment(context, arguments);
          case "create_milestone" -> milestoneTools.createMilestone(context, arguments);
          case "update_milestone" -> milestoneTools.updateMilestone(context, arguments);
          case "delete_milestone" ->
              milestoneTools.deleteMilestone(
                  context, argument(arguments, "milestone"), McpToolInputs.version(arguments));
          default -> throw new IllegalStateException("Unregistered write tool");
        };
    ObjectNode result = mapper.createObjectNode();
    result.put("resultType", "complete");
    result.putArray("content").addObject().put("type", "text").put("text", text);
    return Optional.of(result);
  }

  private void requireAuthorization(McpAuthenticationContext context, McpScope scope) {
    boolean allowed =
        context.permits(context.workspaceId(), scope)
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
    if (!allowed) {
      throw new McpToolInputException("You don't have access to this tool.");
    }
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
      JsonNode property = schema.get("properties").get(field.getKey());
      boolean valid =
          property != null
              && (property.path("type").asText().equals("integer")
                  ? field.getValue().isIntegralNumber()
                      && (field.getKey().equals("version")
                          ? field.getValue().canConvertToLong()
                          : field.getValue().canConvertToInt())
                  : field.getValue().isTextual());
      if (!valid) {
        throw new McpToolInputException(
            "Unknown argument or invalid argument type: " + field.getKey());
      }
    }
    for (JsonNode field : schema.path("required")) {
      if (argument(arguments, field.asText()).isEmpty()) {
        throw new McpToolInputException(field.asText() + " is required");
      }
    }
  }
}
