package works.momens.server.web.workspace.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import works.momens.server.workspace.membership.AssignableWorkspaceRole;

@Schema(description = "워크스페이스 멤버 수정 요청")
public record UpdateWorkspaceMemberRequest(
    @Schema(
            description = "멤버에게 새로 부여할 role입니다. owner는 워크스페이스 생성 시에만 지정되므로 부여할 수 없습니다.",
            example = "admin")
        @NotNull
        AssignableWorkspaceRole role) {}
