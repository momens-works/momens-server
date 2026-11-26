package works.momens.server.web.user.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import works.momens.server.user.UserProfile;

@Schema(description = "내 프로필 응답")
public record MeResponse(@Schema(description = "사용자 프로필") UserResponse user) {

  public static MeResponse from(UserProfile profile) {
    return new MeResponse(UserResponse.from(profile));
  }
}
