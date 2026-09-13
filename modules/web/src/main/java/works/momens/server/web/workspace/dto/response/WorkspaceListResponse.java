package works.momens.server.web.workspace.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import works.momens.server.workspace.core.WorkspaceDetail;

/** {@code GET /api/workspaces} 응답. 빈 결과는 {@code null}이 아니라 빈 배열입니다. */
@Schema(description = "워크스페이스 목록 응답")
public record WorkspaceListResponse(
    @Schema(description = "요청자가 멤버인 워크스페이스 목록(생성 시각 내림차순). 결과가 없으면 빈 배열입니다.")
        List<WorkspaceResponse> workspaces) {

  public static WorkspaceListResponse from(List<WorkspaceDetail> details) {
    return new WorkspaceListResponse(details.stream().map(WorkspaceResponse::from).toList());
  }
}
