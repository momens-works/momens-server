package works.momens.server.web.source;

import java.security.Principal;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import works.momens.server.common.api.CurrentUser;
import works.momens.server.web.dto.response.WebMessageResponse;

@RestController
@RequestMapping("/api/source-connections")
@RequiredArgsConstructor
class SourceConnectionCommandController implements SourceConnectionCommandControllerDocs {

  private final SourceConnectionCommandService sourceConnectionCommandService;

  @Override
  @PostMapping(path = "/{connectionId}/resync", version = "1")
  public WebMessageResponse requestResync(@PathVariable UUID connectionId, Principal principal) {
    sourceConnectionCommandService.requestResync(connectionId, CurrentUser.id(principal));
    return new WebMessageResponse("resync requested");
  }
}
