package works.momens.server.mobile.user.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import works.momens.server.user.UserProfile;

/**
 * {@code GET /api/mobile/me}의 응답 DTO입니다. 응답 스키마는 {@code docs/spec/openapi.json}에서 확인할 수 있습니다.
 *
 * <p>springdoc은 클래스의 단순 이름을 기준으로 컴포넌트 스키마를 생성합니다. 따라서 웹 API의 {@code user.MeResponse}와 이 record가 동일한
 * {@code MeResponse} 스키마명으로 충돌하므로, {@code name}을 지정해 스키마를 구분합니다.
 *
 * <p>Bootstrap 응답의 {@code me}와 필드 구성은 같지만 DTO는 공유하지 않습니다. 앱 진입 화면과 마이페이지의 API 계약을 독립적으로 변경할 수 있도록
 * 하기 위함입니다.
 */
@Schema(name = "MobileMe", description = "현재 로그인한 사용자의 계정 정보")
public record MeResponse(
    @Schema(description = "사용자 식별자", example = "5d2f7f3a-5db1-4f2c-8b9e-13607dd1f5e8") UUID id,
    @Schema(description = "이메일", example = "user@example.com") String email,
    @Schema(description = "이름", example = "김민지") String name,
    @Schema(description = "프로필 사진 URL. 설정하지 않은 경우 `null`로 반환됩니다.", nullable = true)
        String avatarUrl) {

  public static MeResponse from(UserProfile profile) {
    return new MeResponse(profile.id(), profile.email(), profile.name(), profile.avatarUrl());
  }
}
