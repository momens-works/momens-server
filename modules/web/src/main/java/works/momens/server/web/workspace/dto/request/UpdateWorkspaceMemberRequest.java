package works.momens.server.web.workspace.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import works.momens.server.workspace.membership.AssignableWorkspaceRole;

@Schema(description = "워크스페이스 멤버 수정 요청")
public record UpdateWorkspaceMemberRequest(
    @Schema(
            description = "부여할 역할",
            example = "admin",
            implementation = AssignableWorkspaceRole.class)
        String role) {}
