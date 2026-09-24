package works.momens.server.mcp.transport;

import tools.jackson.databind.JsonNode;

public record McpToolDefinition(String name, String description, JsonNode inputSchema) {}
