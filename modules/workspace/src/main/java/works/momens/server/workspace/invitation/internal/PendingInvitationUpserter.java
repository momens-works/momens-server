package works.momens.server.workspace.invitation.internal;

import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 대기 중인 워크스페이스 초대를 생성하거나 기존 초대를 갱신합니다.
 *
 * <p>같은 워크스페이스에 같은 이메일로 대기 중인 초대가 있으면 새 행을 생성하지 않고 기존 행의 역할, 토큰, 만료 시각을 갱신합니다. 부분 UNIQUE 인덱스를 대상으로
 * 하는 단일 INSERT 쿼리가 필요하지만 JPA로 표현할 수 없어 이 작업만 SQL로 처리합니다.
 */
class PendingInvitationUpserter {

  private static final String UPSERT =
      """
      INSERT INTO workspace_invitations (
          id, workspace_id, email, role, inviter_id, token_hash, status, expires_at, created_at, updated_at
      )
      VALUES (:id, :workspaceId, :email, :role, :inviterId, :tokenHash, 'pending', :expiresAt, :now, :now)
      ON CONFLICT (workspace_id, lower(email)) WHERE status = 'pending'
      DO UPDATE SET
          role = EXCLUDED.role,
          inviter_id = EXCLUDED.inviter_id,
          token_hash = EXCLUDED.token_hash,
          expires_at = EXCLUDED.expires_at,
          updated_at = EXCLUDED.updated_at
      RETURNING id
      """;

  private final JdbcClient jdbcClient;

  PendingInvitationUpserter(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  UUID upsert(
      UUID workspaceId,
      String email,
      String role,
      UUID inviterId,
      String tokenHash,
      Instant expiresAt,
      Instant now) {
    return jdbcClient
        .sql(UPSERT)
        .param("id", UUID.randomUUID())
        .param("workspaceId", workspaceId)
        .param("email", email)
        .param("role", role)
        .param("inviterId", inviterId)
        .param("tokenHash", tokenHash)
        .param("expiresAt", java.sql.Timestamp.from(expiresAt))
        .param("now", java.sql.Timestamp.from(now))
        .query(UUID.class)
        .single();
  }
}
