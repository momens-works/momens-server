package works.momens.server.mcp.grant;

import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** OAuth scopes that can be granted to an MCP client. */
public enum McpScope {
  PROJECTS_READ("mcp:projects:read"),
  MEMBERS_READ("mcp:members:read"),
  MILESTONES_READ("mcp:milestones:read"),
  MILESTONES_WRITE("mcp:milestones:write"),
  TASKS_READ("mcp:tasks:read"),
  TASKS_WRITE("mcp:tasks:write");

  private final String value;

  McpScope(String value) {
    this.value = value;
  }

  public String value() {
    return value;
  }

  /** Validates scopes and returns them in the stable protocol order without duplicates. */
  public static List<String> normalize(Collection<String> values) {
    if (values == null || values.isEmpty()) {
      throw new IllegalArgumentException("MCP grant scopes must not be empty");
    }
    Set<String> requested = new LinkedHashSet<>(values);
    List<String> normalized =
        Arrays.stream(values()).map(McpScope::value).filter(requested::contains).toList();
    if (normalized.size() != requested.size()) {
      throw new IllegalArgumentException("MCP grant contains an unsupported scope");
    }
    return normalized;
  }
}
