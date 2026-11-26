package works.momens.server.user;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserService {

  String PROVIDER_GOOGLE = "google";

  /**
   * 검증된 외부 신원 정보를 기준으로 사용자를 조회하거나 생성합니다.
   *
   * <p>로그인 수단으로 사용자를 조회하고, 연결된 사용자가 없으면 새로운 사용자를 생성합니다. 이메일로 기존 사용자를 찾지 않으므로 같은 이메일의 사용자가 이미 있어도
   * 새로운 사용자를 생성합니다(ADR-0016 결정 5).
   *
   * <p>기존 사용자의 이름은 유지하고 이메일과 프로필 이미지는 전달된 값으로 갱신합니다.
   */
  UserProfile findOrCreateByIdentity(
      String provider, String providerUserId, String email, String name, String avatarUrl);

  /**
   * 로그인 수단 없이 이메일을 기준으로 사용자를 조회하거나 생성합니다.
   *
   * <p>dev 전용 토큰 발급처럼 외부 신원을 사용하지 않는 경로에서 호출됩니다. 이 메서드로 생성된 사용자에는 로그인 수단이 연결되지 않습니다(ADR-0016). 기존
   * 사용자의 이름은 유지하고 프로필 이미지는 전달된 값으로 갱신합니다. 같은 이메일의 사용자가 여러 명이면 {@link
   * UserErrorCode#USER_EMAIL_AMBIGUOUS} 오류를 발생시킵니다.
   *
   * <p>조회와 생성이 원자적이지 않아 같은 이메일로 동시에 호출하면 같은 이메일의 사용자가 중복 생성될 수 있습니다. dev 전용 경로이므로 이 경합은 허용합니다.
   */
  UserProfile findOrCreate(String email, String name, String avatarUrl);

  /**
   * email로 사용자 프로필을 조회합니다. 없으면 빈 {@link Optional}을 돌려줍니다. 읽기만 하므로 {@link #findOrCreate}와 달리 기존 프로필을
   * 바꾸지 않습니다.
   *
   * <p>이메일은 사용자 식별자가 아니므로 같은 이메일의 사용자가 여러 명일 수 있습니다. 이 경우 한 명을 고르지 않고 {@link
   * UserErrorCode#USER_EMAIL_AMBIGUOUS} 오류를 발생시킵니다.
   */
  Optional<UserProfile> findByEmail(String email);

  /** 사용자 프로필 조회. 없으면 {@link UserErrorCode#USER_NOT_FOUND}. */
  UserProfile getProfile(UUID userId);

  /** 없는 id는 결과에서 생략하며 반환 순서는 보장하지 않습니다. 순서가 필요하면 호출자가 정렬합니다. */
  List<UserProfile> getProfiles(Collection<UUID> userIds);

  /** 프로필 부분 수정. {@code null} 필드는 변경하지 않습니다. 없으면 {@link UserErrorCode#USER_NOT_FOUND}. */
  UserProfile updateProfile(UUID userId, String name, String jobRole);

  /**
   * 모바일 앱 온보딩 완료를 기록합니다. 이미 온보딩을 완료한 사용자는 최초 완료 시각을 유지합니다. 사용자가 존재하지 않으면 {@link
   * UserErrorCode#USER_NOT_FOUND} 오류를 발생시킵니다.
   */
  void completeMobileOnboarding(UUID userId);

  /** 모바일 앱 온보딩 완료 여부를 조회합니다. 사용자가 존재하지 않으면 {@link UserErrorCode#USER_NOT_FOUND} 오류를 발생시킵니다. */
  boolean isMobileOnboardingCompleted(UUID userId);
}
