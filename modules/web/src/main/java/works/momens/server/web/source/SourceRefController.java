package works.momens.server.web.source;

import java.security.Principal;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import works.momens.server.common.api.CurrentUser;
import works.momens.server.web.source.dto.response.WebSourceRefResponse;

@RestController
@RequestMapping("/api/source-refs")
@RequiredArgsConstructor
class SourceRefController implements SourceRefControllerDocs {

  private final SourceRefService sourceRefService;

  @Override
  @PostMapping(path = "/{sourceRefId}/verify", version = "1")
  public WebSourceRefResponse verify(@PathVariable UUID sourceRefId, Principal principal) {
    return WebSourceRefResponse.from(
        sourceRefService.verify(sourceRefId, CurrentUser.id(principal)));
  }
}
