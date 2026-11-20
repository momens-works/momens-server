package works.momens.server.web.workspace;

import jakarta.validation.Valid;
import java.security.Principal;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import works.momens.server.common.api.CurrentUser;
import works.momens.server.web.dto.response.WebMessageResponse;
import works.momens.server.web.workspace.dto.request.AddWorkspaceMemberRequest;
import works.momens.server.web.workspace.dto.request.CreateWorkspaceInvitationRequest;
import works.momens.server.web.workspace.dto.response.WorkspaceInvitationResponse;
import works.momens.server.web.workspace.dto.response.WorkspaceInvitationsResponse;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}")
@RequiredArgsConstructor
class WorkspaceInvitationController implements WorkspaceInvitationControllerDocs {

  private final WorkspaceInvitationService workspaceInvitationService;

  @Override
  @GetMapping(path = "/invitations", version = "1")
  public WorkspaceInvitationsResponse list(@PathVariable UUID workspaceId, Principal principal) {
    return WorkspaceInvitationsResponse.from(
        workspaceInvitationService.list(workspaceId, CurrentUser.id(principal)));
  }

  @Override
  @PostMapping(path = "/invitations", version = "1")
  @ResponseStatus(HttpStatus.CREATED)
  public WorkspaceInvitationResponse create(
      @PathVariable UUID workspaceId,
      @Valid @RequestBody CreateWorkspaceInvitationRequest request,
      Principal principal) {
    return WorkspaceInvitationResponse.from(
        workspaceInvitationService.create(
            workspaceId, CurrentUser.id(principal), request.email(), request.role()));
  }

  @Override
  @PostMapping(path = "/invitations/{invitationId}/resend", version = "1")
  public WorkspaceInvitationResponse resend(
      @PathVariable UUID workspaceId, @PathVariable UUID invitationId, Principal principal) {
    return WorkspaceInvitationResponse.from(
        workspaceInvitationService.resend(workspaceId, CurrentUser.id(principal), invitationId));
  }

  @Override
  @PostMapping(path = "/invitations/{invitationId}/revoke", version = "1")
  public WorkspaceInvitationResponse revoke(
      @PathVariable UUID workspaceId, @PathVariable UUID invitationId, Principal principal) {
    return WorkspaceInvitationResponse.from(
        workspaceInvitationService.revoke(workspaceId, CurrentUser.id(principal), invitationId));
  }

  @Override
  @PostMapping(path = "/invite", version = "1")
  public WebMessageResponse invite(
      @PathVariable UUID workspaceId,
      @Valid @RequestBody AddWorkspaceMemberRequest request,
      Principal principal) {
    workspaceInvitationService.addMember(
        workspaceId, CurrentUser.id(principal), request.email(), request.role());
    return new WebMessageResponse("invited");
  }
}
