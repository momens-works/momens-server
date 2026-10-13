package works.momens.server.mobile.bootstrap;

import java.security.Principal;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import works.momens.server.common.api.CurrentUser;
import works.momens.server.mobile.bootstrap.dto.response.BootstrapResponse;

@RestController
@RequiredArgsConstructor
class BootstrapController implements BootstrapControllerDocs {

  private final BootstrapService bootstrapService;

  @Override
  @GetMapping(path = "/api/mobile/bootstrap", version = "1")
  public BootstrapResponse getBootstrap(Principal principal) {
    return BootstrapResponse.from(bootstrapService.load(CurrentUser.id(principal)));
  }
}
