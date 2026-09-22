package works.momens.server.mcp.grant.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import works.momens.server.common.persistence.BaseEntity;
import works.momens.server.mcp.grant.CreateMcpGrantCommand;
import works.momens.server.mcp.grant.McpScope;

@Getter
@Entity
@Table(name = "mcp_grants")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class McpGrant extends BaseEntity {

  @Column(name = "user_id", nullable = false, columnDefinition = "uuid")
  private UUID userId;

  @Column(name = "client_id", nullable = false)
  private String clientId;

  @Column(name = "workspace_id", nullable = false, columnDefinition = "uuid")
  private UUID workspaceId;

  @JdbcTypeCode(SqlTypes.ARRAY)
  @Column(name = "scopes", nullable = false, columnDefinition = "text[]")
  private List<String> scopes;

  @Column(name = "approved_at", nullable = false)
  private Instant approvedAt;

  @Column(name = "revoked_at")
  private Instant revokedAt;

  static McpGrant create(CreateMcpGrantCommand command, Instant approvedAt) {
    McpGrant grant = new McpGrant();
    grant.userId = command.userId();
    grant.clientId = command.clientId();
    grant.workspaceId = command.workspaceId();
    grant.scopes = List.copyOf(McpScope.normalize(command.scopes()));
    grant.approvedAt = approvedAt;
    return grant;
  }

  void revoke(Instant revokedAt) {
    if (this.revokedAt == null) {
      this.revokedAt = revokedAt;
    }
  }
}
