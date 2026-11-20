package works.momens.server.notification.device;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import works.momens.server.common.persistence.BaseEntity;

/**
 * push 설치(FID 단위) 상태.
 *
 * <p>FID가 canonical identity라 {@code firebase_installation_id UNIQUE}이고, 활성 FCM token 중복은 활성 행 부분
 * unique 제약으로 막는다. 로그아웃·무효 token은 물리 삭제 대신 비활성화로 반영해 같은 사용자의 재등록과 token lifecycle을 안전하게 처리한다.
 */
@Getter
@Entity
@Table(name = "push_installations")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class PushInstallation extends BaseEntity {

  @Column(name = "firebase_installation_id", nullable = false)
  private String firebaseInstallationId;

  @Column(name = "user_id", nullable = false, columnDefinition = "uuid")
  private UUID userId;

  @Column(name = "fcm_registration_token", nullable = false)
  private String fcmRegistrationToken;

  @Column(nullable = false)
  private String platform;

  @Column(nullable = false)
  private boolean active;

  @Column(name = "deactivated_at")
  private Instant deactivatedAt;

  @Builder
  PushInstallation(
      String firebaseInstallationId, UUID userId, String fcmRegistrationToken, String platform) {
    this.firebaseInstallationId = firebaseInstallationId;
    this.userId = userId;
    this.fcmRegistrationToken = fcmRegistrationToken;
    this.platform = platform;
    this.active = true;
  }

  /** 같은 FID의 재등록. token을 갱신하고 활성화하며, 다른 사용자의 FID면 소유권을 현재 사용자에게 이전한다. */
  void reassign(UUID userId, String fcmRegistrationToken) {
    this.userId = userId;
    this.fcmRegistrationToken = fcmRegistrationToken;
    this.active = true;
    this.deactivatedAt = null;
  }

  /**
   * 발송 대상에서 제외한다(로그아웃 해제·token 이동·무효 token). 이미 비활성이면 최초 비활성화 시각을 유지한다.
   *
   * @return 이번 호출에서 활성 설치를 비활성화했으면 true.
   */
  boolean deactivate() {
    if (!active) {
      return false;
    }
    this.active = false;
    this.deactivatedAt = Instant.now();
    return true;
  }
}
