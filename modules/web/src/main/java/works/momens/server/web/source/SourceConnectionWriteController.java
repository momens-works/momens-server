package works.momens.server.web.source;

import jakarta.validation.Valid;
import java.security.Principal;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import works.momens.server.common.api.CurrentUser;
import works.momens.server.web.dto.response.WebMessageResponse;
import works.momens.server.web.source.dto.request.ConfigureFigmaRequest;
import works.momens.server.web.source.dto.response.SourceConnectionResponse;

@RestController
@RequestMapping("/api/source-connections")
@RequiredArgsConstructor
class SourceConnectionWriteController implements SourceConnectionWriteControllerDocs {

  private final SourceConnectionService sourceConnectionService;

  @Override
  @PostMapping(path = "/{connectionId}/figma/configure", version = "1")
  public SourceConnectionResponse configureFigma(
      @PathVariable UUID connectionId,
      @Valid @RequestBody ConfigureFigmaRequest request,
      Principal principal) {
    return SourceConnectionResponse.from(
        sourceConnectionService.configureFigma(
            connectionId, CurrentUser.id(principal), request.toCommand()));
  }

  @Override
  @PostMapping(path = "/{connectionId}/disable", version = "1")
  public WebMessageResponse disable(@PathVariable UUID connectionId, Principal principal) {
    sourceConnectionService.disable(connectionId, CurrentUser.id(principal));
    return new WebMessageResponse("disabled");
  }
}
