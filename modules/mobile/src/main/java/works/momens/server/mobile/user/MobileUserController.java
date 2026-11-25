package works.momens.server.mobile.user;

import java.security.Principal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import works.momens.server.common.api.CurrentUser;
import works.momens.server.mobile.user.dto.response.MeResponse;
import works.momens.server.user.UserService;

@RestController
@RequiredArgsConstructor
class MobileUserController implements MobileUserControllerDocs {

  private final UserService userService;

  @Override
  @GetMapping(path = "/api/mobile/me", version = "1")
  public MeResponse getMe(Principal principal) {
    return MeResponse.from(userService.getProfile(CurrentUser.id(principal)));
  }

  @Override
  @PutMapping(path = "/api/mobile/me/onboarding-completion", version = "1")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void completeOnboarding(Principal principal) {
    userService.completeMobileOnboarding(CurrentUser.id(principal));
  }
}
