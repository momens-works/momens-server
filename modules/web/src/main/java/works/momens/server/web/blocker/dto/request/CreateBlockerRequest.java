package works.momens.server.web.blocker.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import java.util.UUID;

@Schema(description = "작업 차단 사유 생성 요청")
public record CreateBlockerRequest(
    @Schema(description = "대상 워크스페이스. 생략하면 작업의 프로젝트에서 확인합니다.") UUID workspaceId,
    @NotEmpty @Schema(description = "차단 사유 설명", example = "배포 권한 승인이 필요합니다.") String description) {}
