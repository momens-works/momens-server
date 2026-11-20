package works.momens.server.web.workspace.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import works.momens.server.workspace.membership.AssignableWorkspaceRole;

@Schema(description = "초대 없이 워크스페이스 멤버를 바로 추가하는 요청")
public record AddWorkspaceMemberRequest(
    @Schema(description = "추가할 사용자의 이메일", example = "jinsu@momens.works") String email,
    @Schema(
            description = "추가할 멤버에게 부여할 role입니다. owner는 워크스페이스 생성 시에만 지정되므로 부여할 수 없습니다.",
            example = "member")
        @NotNull
        AssignableWorkspaceRole role) {}
