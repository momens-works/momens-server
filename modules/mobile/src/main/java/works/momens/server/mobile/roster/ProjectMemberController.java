package works.momens.server.mobile.roster;

import java.security.Principal;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import works.momens.server.common.api.CurrentUser;
import works.momens.server.mobile.roster.dto.response.ProjectMembersResponse;

@RestController
@RequiredArgsConstructor
class ProjectMemberController implements ProjectMemberControllerDocs {

  private final ProjectMemberService projectMemberService;

  @Override
  @GetMapping(path = "/api/mobile/projects/{projectId}/members", version = "1")
  public ProjectMembersResponse listMembers(
      @PathVariable UUID projectId,
      @RequestParam(required = false) String query,
      Principal principal) {
    return ProjectMembersResponse.from(
        projectMemberService.list(projectId, CurrentUser.id(principal), query));
  }
}
