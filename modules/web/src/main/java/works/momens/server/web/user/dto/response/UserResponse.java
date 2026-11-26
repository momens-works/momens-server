package works.momens.server.web.user.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import works.momens.server.user.UserProfile;

@Schema(description = "사용자 프로필")
public record UserResponse(
    @Schema(description = "사용자 식별자", example = "5d2f7f3a-5db1-4f2c-8b9e-13607dd1f5e8") UUID id,
    @Schema(description = "이메일", example = "user@example.com") String email,
    @Schema(description = "이름", example = "홍길동") String name,
    @JsonInclude(JsonInclude.Include.NON_NULL)
        @Schema(
            description = "아바타 URL. 미설정 시 응답에서 생략됩니다.",
            example = "https://cdn.momens.works/avatars/abc.png",
            nullable = true)
        String avatarUrl,
    @Schema(description = "직무. 미설정이면 null로 포함됩니다.", example = "Engineer", nullable = true)
        String jobRole,
    @Schema(description = "생성 시각(UTC)", example = "2026-06-24T00:00:00Z") Instant createdAt,
    @Schema(description = "수정 시각(UTC)", example = "2026-06-24T00:00:00Z") Instant updatedAt) {

  public static UserResponse from(UserProfile p) {
    return new UserResponse(
        p.id(), p.email(), p.name(), p.avatarUrl(), p.jobRole(), p.createdAt(), p.updatedAt());
  }
}
