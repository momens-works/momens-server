package works.momens.server.web.workspace.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import works.momens.server.mcp.grant.McpGrantConnection;

@Schema(description = "현재 사용자가 승인한 활성 MCP 연결 목록")
public record McpGrantsResponse(List<Grant> grants) {
  @JsonInclude(JsonInclude.Include.NON_NULL)
  @Schema(name = "McpGrantResponse", description = "현재 사용자의 활성 MCP 연결")
  public record Grant(
      @Schema(description = "연결 식별자") UUID id,
      @Schema(description = "OAuth 공개 클라이언트 식별자") String clientId,
      @Schema(description = "클라이언트 표시 이름", example = "MCP client") String clientName,
      @Schema(description = "승인한 사용자 식별자") UUID userId,
      @Schema(description = "워크스페이스 식별자") UUID workspaceId,
      @Schema(description = "승인한 scope 목록") List<String> scopes,
      @Schema(description = "마지막 토큰 검증 성공 시각. 사용 전에는 생략합니다.", nullable = true) Instant lastUsedAt,
      @Schema(description = "연결 생성 시각") Instant createdAt) {
    public static Grant from(McpGrantConnection grant, String clientName) {
      return new Grant(
          grant.id(),
          grant.clientId(),
          clientName,
          grant.userId(),
          grant.workspaceId(),
          grant.scopes(),
          grant.lastUsedAt(),
          grant.createdAt());
    }
  }
}
