package works.momens.server.mobile.appupdate.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

/** 배포된 앱이 계속 사용하는 응답이므로 기존 필드의 제거·이름 변경을 허용하지 않습니다. */
@Schema(description = "모바일 앱 업데이트 필요 여부 응답")
public record AppUpdateResponse(
    @Schema(description = "앱 버전이 해당 플랫폼의 최소 지원 버전보다 낮아 업데이트가 필요하면 true입니다.")
        boolean updateRequired) {}
