package works.momens.server.user.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import works.momens.server.common.persistence.BaseEntity;

/**
 * 한 사용자가 여러 로그인 수단을 가질 수 있어 별도 테이블로 관리합니다.
 *
 * <p>provider는 enum 이름 대신 DB의 소문자 값을 저장하며 CHECK 제약으로 제한합니다. 운영 쓰기는 네이티브 INSERT를 사용하므로 감사 필드는 스키마
 * 기본값으로 채웁니다. 엔티티 생성자는 스키마 검증 테스트에서 사용합니다.
 */
@Getter
@Entity
@Table(name = "user_identities")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class UserIdentity extends BaseEntity {

  @Column(name = "user_id", nullable = false, columnDefinition = "uuid")
  private UUID userId;

  @Column(nullable = false)
  private String provider;

  @Column(name = "provider_user_id", nullable = false)
  private String providerUserId;

  @Builder
  private UserIdentity(UUID userId, String provider, String providerUserId) {
    this.userId = userId;
    this.provider = provider;
    this.providerUserId = providerUserId;
  }
}
