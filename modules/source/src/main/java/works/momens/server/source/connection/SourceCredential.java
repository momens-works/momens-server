package works.momens.server.source.connection;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.domain.Persistable;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * 연결 식별자가 기본 키이므로 UUID를 새로 발급하는 공통 베이스를 사용하지 않습니다.
 *
 * <p>감사 필드가 비어 있으면 {@link Persistable}에서 새 엔티티로 판정합니다. 토큰은 암호문만 보관합니다.
 */
@Getter
@Entity
@Table(name = "source_credentials")
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SourceCredential implements Persistable<UUID> {

  @Id
  @Column(name = "connection_id", columnDefinition = "uuid")
  private UUID connectionId;

  @Column(name = "access_token_enc", nullable = false)
  private byte[] accessTokenEnc;

  @Column(name = "refresh_token_enc")
  private byte[] refreshTokenEnc;

  @Column(name = "token_type")
  private String tokenType;

  @Column private String scope;

  @Column(name = "expires_at")
  private Instant expiresAt;

  @CreatedDate
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @LastModifiedDate
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Builder
  SourceCredential(
      UUID connectionId,
      byte[] accessTokenEnc,
      byte[] refreshTokenEnc,
      String tokenType,
      String scope,
      Instant expiresAt) {
    this.connectionId = connectionId;
    this.accessTokenEnc = accessTokenEnc;
    this.refreshTokenEnc = refreshTokenEnc;
    this.tokenType = tokenType;
    this.scope = scope;
    this.expiresAt = expiresAt;
  }

  public void replaceTokens(
      byte[] accessTokenEnc,
      byte[] refreshTokenEnc,
      String tokenType,
      String scope,
      Instant expiresAt) {
    this.accessTokenEnc = accessTokenEnc;
    this.refreshTokenEnc = refreshTokenEnc;
    this.tokenType = tokenType;
    this.scope = scope;
    this.expiresAt = expiresAt;
  }

  @Override
  public UUID getId() {
    return connectionId;
  }

  @Override
  public boolean isNew() {
    return createdAt == null;
  }
}
