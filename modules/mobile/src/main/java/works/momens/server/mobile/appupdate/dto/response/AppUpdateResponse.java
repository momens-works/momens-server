package works.momens.server.mobile.appupdate.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * {@code GET /api/mobile/app-update}의 응답입니다. 형식은 docs/spec/mobile-api.md의 앱 업데이트 절을 따릅니다. 이미 배포된
 * 앱에서 사용하는 응답이므로 기존 필드를 제거하거나 이름을 변경하지 않습니다.
 */
@Schema(description = "모바일 앱 업데이트 필요 여부 응답")
public record AppUpdateResponse(
    @Schema(description = "앱 버전이 해당 플랫폼의 최소 지원 버전보다 낮아 업데이트가 필요하면 true입니다.")
        boolean updateRequired) {}
