package works.momens.server.mcp.grant;

import java.time.Instant;
import java.util.UUID;

/** Port used by grant revocation to invalidate token families issued for the grant. */
public interface McpTokenFamilyRevoker {

  void revokeByGrantId(UUID grantId, Instant revokedAt);
}
