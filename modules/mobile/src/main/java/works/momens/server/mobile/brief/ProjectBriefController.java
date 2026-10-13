package works.momens.server.mobile.brief;

import java.security.Principal;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import works.momens.server.common.api.CurrentUser;
import works.momens.server.mobile.brief.dto.response.BriefResponse;
import works.momens.server.mobile.brief.dto.response.BriefSignalSummaryPageResponse;

@RestController
@RequestMapping("/api/mobile")
@RequiredArgsConstructor
class ProjectBriefController implements ProjectBriefControllerDocs {

  private final ProjectBriefService projectBriefService;

  @Override
  @GetMapping(path = "/projects/{projectId}/brief", version = "1")
  public BriefResponse getBrief(@PathVariable UUID projectId, Principal principal) {
    return BriefResponse.from(projectBriefService.getBrief(projectId, CurrentUser.id(principal)));
  }

  @Override
  @GetMapping(path = "/projects/{projectId}/brief/signal-summary", version = "1")
  public BriefSignalSummaryPageResponse getSignalSummaryPage(
      @PathVariable UUID projectId,
      @RequestParam(name = "filter", defaultValue = "all") String filter,
      @RequestParam(name = "cursor", required = false) String cursor,
      @RequestParam(name = "limit", required = false) Integer limit,
      Principal principal) {
    return BriefSignalSummaryPageResponse.from(
        projectBriefService.getSignalSummaryPage(
            projectId, CurrentUser.id(principal), filter, cursor, limit));
  }
}
