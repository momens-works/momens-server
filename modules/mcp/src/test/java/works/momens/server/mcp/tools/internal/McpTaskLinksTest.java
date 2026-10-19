package works.momens.server.mcp.tools.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import works.momens.server.mcp.configuration.McpWebProperties;
import works.momens.server.project.task.TaskSnapshot;
import works.momens.server.workspace.core.WorkspaceDetail;
import works.momens.server.workspace.core.WorkspaceReader;

class McpTaskLinksTest {
  private final UUID workspaceId = UUID.randomUUID();
  private final UUID projectId = UUID.randomUUID();
  private final UUID taskId = UUID.randomUUID();
  private final WorkspaceReader workspaces = mock(WorkspaceReader.class);

  @ParameterizedTest
  @ValueSource(
      strings = {"http://localhost:5173", "https://dev.example.com/", "https://app.example.com"})
  void usesConfiguredOriginAndEncodesSegmentsAndQuery(String origin) {
    when(workspaces.findById(workspaceId))
        .thenReturn(
            Optional.of(new WorkspaceDetail(workspaceId, "Team", "팀 /?&#%", null, null, null)));
    McpTaskLinks links = new McpTaskLinks(new McpWebProperties(URI.create(origin)), workspaces);

    assertThat(links.taskUrl(workspaceId, task("MOM-1&tab=context+#%")))
        .isEqualTo(
            origin.replaceAll("/$", "")
                + "/%ED%8C%80%20%2F%3F%26%23%25/projects/"
                + projectId
                + "?tab=tasks&task=MOM-1%26tab%3Dcontext%2B%23%25");
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"MOM-1052"})
  void matchesWebTaskIdentity(String label) {
    when(workspaces.findById(workspaceId))
        .thenReturn(
            Optional.of(new WorkspaceDetail(workspaceId, "Team", "team", null, null, null)));
    McpTaskLinks links =
        new McpTaskLinks(new McpWebProperties(URI.create("https://app.example.com")), workspaces);

    assertThat(links.taskUrl(workspaceId, task(label)))
        .isEqualTo(
            "https://app.example.com/team/projects/"
                + projectId
                + "?tab=tasks&task="
                + (label == null ? taskId : label));
  }

  private TaskSnapshot task(String label) {
    return new TaskSnapshot(
        taskId,
        workspaceId,
        projectId,
        null,
        label,
        "Task",
        null,
        "todo",
        "medium",
        null,
        null,
        null,
        null,
        null,
        0);
  }
}
