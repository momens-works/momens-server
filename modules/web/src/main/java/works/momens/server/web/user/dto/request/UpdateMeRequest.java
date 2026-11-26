package works.momens.server.web.user.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(description = "프로필 수정 요청. 제공된(non-null) 필드만 갱신합니다.")
public record UpdateMeRequest(
    @Schema(description = "이름", example = "홍길동", maxLength = 255, nullable = true)
        @Size(max = 255)
        @Pattern(regexp = "(?U).*\\S.*")
        String name,
    @Schema(description = "직무", example = "Engineer", maxLength = 255, nullable = true)
        @Size(max = 255)
        String jobRole) {}
