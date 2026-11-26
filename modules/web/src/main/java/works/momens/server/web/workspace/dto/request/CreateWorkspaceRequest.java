package works.momens.server.web.workspace.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * {@code POST /api/workspaces} 요청입니다.
 *
 * <p>{@code name}만 필수입니다. 웹은 해당 값만 전달하며 {@code description}과 {@code slug}는 전달하지 않습니다. {@code slug}를
 * 전달하면 형식, 예약어, 중복 여부를 검증하고, 생략하면 워크스페이스 이름을 기준으로 생성합니다.
 */
@Schema(description = "워크스페이스 생성 요청")
public record CreateWorkspaceRequest(
    @Schema(description = "워크스페이스 이름", requiredMode = Schema.RequiredMode.REQUIRED) String name,
    @Schema(description = "워크스페이스 설명") String description,
    @Schema(description = "워크스페이스 slug. 생략하면 이름을 기준으로 생성합니다.") String slug) {}
