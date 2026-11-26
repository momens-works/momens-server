package works.momens.server.web.user.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import works.momens.server.user.UserProfile;

/** {@code {"user": {...}}} 래퍼. 레거시 {@code GET/PATCH /auth/me}의 응답 shape를 보존합니다. */
@Schema(description = "내 프로필 응답")
public record MeResponse(@Schema(description = "사용자 프로필") UserResponse user) {

  public static MeResponse from(UserProfile profile) {
    return new MeResponse(UserResponse.from(profile));
  }
}
