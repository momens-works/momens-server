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

/**
 * 웹 워크스페이스 멤버 endpoint.
 *
 * <p>성공 응답은 레거시 형식을 유지합니다. 멤버 목록은 {@code members}로 감싸고, 역할 변경과 멤버 제거는 반환할 리소스가 없으므로 {@code
 * message}만 담아 응답합니다.
 */
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
