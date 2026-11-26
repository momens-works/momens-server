package works.momens.server.web.workspace.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "워크스페이스 생성 요청")
public record CreateWorkspaceRequest(
    @Schema(description = "워크스페이스 이름", requiredMode = Schema.RequiredMode.REQUIRED) String name,
    @Schema(description = "워크스페이스 설명") String description,
    @Schema(description = "워크스페이스 slug. 생략하면 이름을 기준으로 생성합니다.") String slug) {}
