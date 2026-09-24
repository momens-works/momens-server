package works.momens.server.mcp.transport;

import tools.jackson.databind.JsonNode;

public record McpToolDefinition(String name, String description, JsonNode inputSchema) {

  public McpToolDefinition {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("Tool name must not be blank");
    }
    if (inputSchema == null || !inputSchema.isObject()) {
      throw new IllegalArgumentException("Tool input schema must be a JSON object");
    }
  }
}
