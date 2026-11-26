package works.momens.server.web.workspace.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "워크스페이스 수정 요청")
public record UpdateWorkspaceRequest(
    @Schema(description = "워크스페이스 이름. 생략하거나 빈 문자열이면 기존 이름을 유지합니다.") String name,
    @Schema(description = "워크스페이스 설명. 생략하거나 빈 문자열이면 기존 설명을 유지합니다.") String description,
    @Schema(description = "워크스페이스 slug. 생략하거나 빈 문자열이면 기존 slug를 유지합니다.") String slug) {}
