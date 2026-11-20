package works.momens.server.web.user;

import jakarta.validation.Valid;
import java.security.Principal;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import works.momens.server.common.api.CurrentUser;
import works.momens.server.user.UserProfile;
import works.momens.server.user.UserService;
import works.momens.server.web.user.dto.request.UpdateMeRequest;
import works.momens.server.web.user.dto.response.MeResponse;

@RestController
@RequiredArgsConstructor
class UserController implements UserControllerDocs {

  private final UserService userService;

  @Override
  @GetMapping(path = "/api/me", version = "1")
  public MeResponse getMe(Principal principal) {
    UserProfile profile = userService.getProfile(CurrentUser.id(principal));
    return MeResponse.from(profile);
  }

  @Override
  @PatchMapping(path = "/api/me", version = "1")
  public MeResponse updateMe(Principal principal, @Valid @RequestBody UpdateMeRequest request) {
    UserProfile profile =
        userService.updateProfile(CurrentUser.id(principal), request.name(), request.jobRole());
    return MeResponse.from(profile);
  }
}
