package works.momens.server.web.workspace;

import jakarta.validation.Valid;
import java.security.Principal;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import works.momens.server.common.api.CurrentUser;
import works.momens.server.web.dto.response.WebMessageResponse;
import works.momens.server.web.workspace.dto.request.UpdateWorkspaceMemberRequest;
import works.momens.server.web.workspace.dto.response.WorkspaceMembersResponse;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/members")
@RequiredArgsConstructor
class WorkspaceMemberController implements WorkspaceMemberControllerDocs {

  private final WorkspaceMemberService workspaceMemberService;

  @Override
  @GetMapping(version = "1")
  public WorkspaceMembersResponse list(@PathVariable UUID workspaceId, Principal principal) {
    return WorkspaceMembersResponse.from(
        workspaceMemberService.list(workspaceId, CurrentUser.id(principal)));
  }

  @Override
  @PatchMapping(path = "/{userId}", version = "1")
  public WebMessageResponse update(
      @PathVariable UUID workspaceId,
      @PathVariable UUID userId,
      @Valid @RequestBody UpdateWorkspaceMemberRequest request,
      Principal principal) {
    workspaceMemberService.changeRole(
        workspaceId, CurrentUser.id(principal), userId, request.role());
    return new WebMessageResponse("updated");
  }

  @Override
  @DeleteMapping(path = "/{userId}", version = "1")
  public WebMessageResponse remove(
      @PathVariable UUID workspaceId, @PathVariable UUID userId, Principal principal) {
    workspaceMemberService.remove(workspaceId, CurrentUser.id(principal), userId);
    return new WebMessageResponse("removed");
  }
}
