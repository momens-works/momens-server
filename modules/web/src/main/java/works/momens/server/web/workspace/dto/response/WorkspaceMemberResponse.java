package works.momens.server.web.workspace.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import works.momens.server.web.workspace.WorkspaceMemberView;
import works.momens.server.workspace.membership.WorkspaceRole;

@Schema(description = "워크스페이스 멤버")
public record WorkspaceMemberResponse(
    @Schema(description = "사용자 식별자") UUID id,
    @Schema(description = "이메일") String email,
    @Schema(description = "이름") String name,
    @Schema(description = "워크스페이스 역할입니다.", implementation = WorkspaceRole.class) String role,
    @Schema(description = "멤버로 등록된 시각") Instant createdAt,
    @Schema(description = "멤버십이 마지막으로 변경된 시각") Instant updatedAt) {

  public static WorkspaceMemberResponse from(WorkspaceMemberView view) {
    return new WorkspaceMemberResponse(
        view.userId(), view.email(), view.name(), view.role(), view.createdAt(), view.updatedAt());
  }
}
