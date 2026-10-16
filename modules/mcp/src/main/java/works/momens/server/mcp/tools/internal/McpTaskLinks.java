package works.momens.server.mcp.tools.internal;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;
import works.momens.server.mcp.configuration.McpWebProperties;
import works.momens.server.project.task.TaskSnapshot;
import works.momens.server.workspace.core.WorkspaceReader;

@Component
@RequiredArgsConstructor
class McpTaskLinks {
  private final McpWebProperties properties;
  private final WorkspaceReader workspaces;

  String taskUrl(UUID workspaceId, TaskSnapshot task) {
    String slug =
        workspaces
            .findById(workspaceId)
            .orElseThrow(() -> new McpToolInputException("Workspace not found."))
            .slug();
    // 웹의 task query는 화면 모델의 id(label이 있으면 label)를 사용합니다.
    String reference = task.label() == null ? task.id().toString() : task.label();
    return UriComponentsBuilder.fromUri(properties.webBaseUri())
        .replacePath(null)
        .pathSegment("{workspace}", "projects", "{project}")
        .queryParam("tab", "tasks")
        .queryParam("task", "{task}")
        .encode()
        .buildAndExpand(slug, task.projectId(), reference)
        .toUriString();
  }
}
