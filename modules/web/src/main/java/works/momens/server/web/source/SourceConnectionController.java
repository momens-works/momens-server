package works.momens.server.web.source;

import java.security.Principal;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import works.momens.server.common.api.CurrentUser;
import works.momens.server.web.source.dto.response.SourceConnectionsResponse;
import works.momens.server.web.source.dto.response.SourceInstallResponse;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/source-connections")
@RequiredArgsConstructor
class SourceConnectionController implements SourceConnectionControllerDocs {

  private final SourceConnectionService sourceConnectionService;

  @Override
  @GetMapping(version = "1")
  public SourceConnectionsResponse list(@PathVariable UUID workspaceId, Principal principal) {
    return SourceConnectionsResponse.from(
        sourceConnectionService.list(workspaceId, CurrentUser.id(principal)));
  }

  @Override
  @GetMapping(path = "/install", version = "1")
  public SourceInstallResponse install(
      @PathVariable UUID workspaceId,
      @RequestParam(name = "provider", required = false) String provider,
      Principal principal) {
    return new SourceInstallResponse(
        sourceConnectionService.beginInstall(workspaceId, CurrentUser.id(principal), provider));
  }
}
