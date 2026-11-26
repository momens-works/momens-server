package works.momens.server.web.workspace;

import java.security.Principal;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import works.momens.server.common.api.CurrentUser;
import works.momens.server.web.workspace.dto.request.CreateWorkspaceRequest;
import works.momens.server.web.workspace.dto.request.UpdateWorkspaceRequest;
import works.momens.server.web.workspace.dto.response.WorkspaceListResponse;
import works.momens.server.web.workspace.dto.response.WorkspaceResponse;
import works.momens.server.web.workspace.dto.response.WorkspaceSlugAvailabilityResponse;
import works.momens.server.web.workspace.dto.response.WorkspaceSnapshotResponse;

/**
 * 웹 워크스페이스 endpoint입니다.
 *
 * <p>{@code /api/workspaces}는 보호 체인의 기본 인증 대상이므로 별도의 보안 설정을 추가하지 않습니다. 현재 사용자 식별자는 {@link
 * CurrentUser#id(Principal)}로 조회합니다({@code docs/rules/code-conventions.md}의 보호 API 규칙).
 */
@RestController
@RequestMapping("/api/workspaces")
@RequiredArgsConstructor
class WorkspaceController implements WorkspaceControllerDocs {

  private final WorkspaceService workspaceService;
  private final WorkspaceSnapshotService workspaceSnapshotService;

  @Override
  @GetMapping(version = "1")
  public WorkspaceListResponse list(Principal principal) {
    return WorkspaceListResponse.from(workspaceService.list(CurrentUser.id(principal)));
  }

  @Override
  @GetMapping(path = "/slug-available", version = "1")
  public WorkspaceSlugAvailabilityResponse slugAvailable(
      @RequestParam(name = "slug", required = false) String slug) {
    return WorkspaceSlugAvailabilityResponse.from(workspaceService.slugAvailability(slug));
  }

  @Override
  @GetMapping(path = "/{workspaceId}", version = "1")
  public WorkspaceResponse get(@PathVariable UUID workspaceId, Principal principal) {
    return WorkspaceResponse.from(workspaceService.get(workspaceId, CurrentUser.id(principal)));
  }

  @Override
  @GetMapping(path = "/{workspaceId}/snapshot", version = "1")
  public WorkspaceSnapshotResponse snapshot(@PathVariable UUID workspaceId, Principal principal) {
    return workspaceSnapshotService.get(workspaceId, CurrentUser.id(principal));
  }

  @Override
  @PostMapping(version = "1")
  @ResponseStatus(HttpStatus.CREATED)
  public WorkspaceResponse createWorkspace(
      @RequestBody CreateWorkspaceRequest request, Principal principal) {
    return WorkspaceResponse.from(
        workspaceService.create(
            CurrentUser.id(principal), request.name(), request.description(), request.slug()));
  }

  @Override
  @PatchMapping(path = "/{workspaceId}", version = "1")
  public WorkspaceResponse update(
      @PathVariable UUID workspaceId,
      @RequestBody UpdateWorkspaceRequest request,
      Principal principal) {
    return WorkspaceResponse.from(
        workspaceService.update(
            workspaceId,
            CurrentUser.id(principal),
            request.name(),
            request.description(),
            request.slug()));
  }
}
