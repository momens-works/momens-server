package works.momens.server.mcp.grant.internal;

import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import works.momens.server.mcp.grant.McpTokenFamilyRevoker;

/** Temporary adapter until the MCP token persistence slice supplies token-family revocation. */
@Component
class NoopMcpTokenFamilyRevoker implements McpTokenFamilyRevoker {

  @Override
  public void revokeByGrantId(UUID grantId, Instant revokedAt) {
    // MOM-0989 replaces this adapter with the MCP token persistence implementation.
  }
}
