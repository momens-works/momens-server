package works.momens.server.user.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import works.momens.server.common.persistence.BaseEntity;

@Getter
@Entity
@Table(name = "users")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class User extends BaseEntity {

  @Column(nullable = false, unique = true)
  private String email;

  @Column(nullable = false)
  private String name;

  @Column(name = "avatar_url")
  private String avatarUrl;

  @Column(name = "job_role")
  private String jobRole;

  @Column(name = "mobile_onboarding_completed_at")
  private Instant mobileOnboardingCompletedAt;

  @Builder
  private User(String email, String name, String avatarUrl, String jobRole) {
    this.email = email;
    this.name = name;
    this.avatarUrl = avatarUrl;
    this.jobRole = jobRole;
  }

  void refreshLoginProfile(String avatarUrl) {
    this.avatarUrl = avatarUrl;
  }

  /** 프로필 부분 수정. {@code null} 필드는 변경하지 않습니다(job_role 비우기는 미지원). */
  void updateProfile(String name, String jobRole) {
    if (name != null) {
      this.name = name;
    }
    if (jobRole != null) {
      this.jobRole = jobRole;
    }
  }

  /** 모바일 앱 온보딩 완료를 기록합니다. 이미 온보딩을 완료한 경우 최초 완료 시각을 유지합니다. */
  void completeMobileOnboarding() {
    if (isMobileOnboardingCompleted()) {
      return;
    }
    this.mobileOnboardingCompletedAt = Instant.now();
  }

  boolean isMobileOnboardingCompleted() {
    return mobileOnboardingCompletedAt != null;
  }
}
