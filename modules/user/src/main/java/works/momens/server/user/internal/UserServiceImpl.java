package works.momens.server.user.internal;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import works.momens.server.common.api.BusinessException;
import works.momens.server.user.UserErrorCode;
import works.momens.server.user.UserProfile;
import works.momens.server.user.UserService;

@Service
@RequiredArgsConstructor
class UserServiceImpl implements UserService {

  private final UserRepository userRepository;
  private final UserIdentityRepository userIdentityRepository;

  @Override
  @Transactional
  public UserProfile findOrCreateByIdentity(
      String provider, String providerUserId, String email, String name, String avatarUrl) {
    Optional<UserIdentity> identity =
        userIdentityRepository.findByProviderAndProviderUserId(provider, providerUserId);
    if (identity.isPresent()) {
      return refreshProfile(identity.get().getUserId(), email, avatarUrl);
    }

    // 같은 로그인 수단으로 최초 로그인 요청이 동시에 들어오면 요청마다 users 행을 만들고, 로그인 수단은 한 요청만 연결합니다.
    // 연결하지 못한 요청은 자기가 만든 행을 지우고 연결에 성공한 사용자로 로그인합니다.
    // 행을 남기면 로그인 수단이 없는 사용자가 그 이메일을 계속 차지해 이메일 조회가 USER_EMAIL_AMBIGUOUS로 실패할 수 있습니다.
    User created = userRepository.saveAndFlush(newUser(email, name, avatarUrl));
    int inserted =
        userIdentityRepository.insertIgnoringConflict(
            UUID.randomUUID(), created.getId(), provider, providerUserId);
    if (inserted == 0) {
      userRepository.delete(created);
      return refreshProfile(winnerUserId(provider, providerUserId), email, avatarUrl);
    }
    return toProfile(created);
  }

  private UUID winnerUserId(String provider, String providerUserId) {
    return userIdentityRepository
        .findByProviderAndProviderUserId(provider, providerUserId)
        .orElseThrow()
        .getUserId();
  }

  private UserProfile refreshProfile(UUID userId, String email, String avatarUrl) {
    User user = userRepository.findById(userId).orElseThrow(() -> notFound(userId));
    return refreshAndReturn(user, email, avatarUrl);
  }

  /**
   * 전달된 이메일과 프로필 이미지를 반영합니다.
   *
   * <p>{@code @LastModifiedDate}는 flush 시점에 updated_at을 갱신합니다. flush하지 않으면 반환하는 프로필에 갱신 전
   * updated_at이 포함되므로 명시적으로 호출합니다.
   */
  private UserProfile refreshAndReturn(User user, String email, String avatarUrl) {
    user.refreshLoginProfile(email, avatarUrl);
    userRepository.flush();
    return toProfile(user);
  }

  @Override
  @Transactional
  public UserProfile findOrCreate(String email, String name, String avatarUrl) {
    Optional<User> existing = findUniqueByEmail(email);
    if (existing.isPresent()) {
      return refreshAndReturn(existing.get(), email, avatarUrl);
    }
    return toProfile(userRepository.saveAndFlush(newUser(email, name, avatarUrl)));
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<UserProfile> findByEmail(String email) {
    return findUniqueByEmail(email).map(UserServiceImpl::toProfile);
  }

  private Optional<User> findUniqueByEmail(String email) {
    List<User> users = userRepository.findTop2ByEmail(email);
    if (users.size() > 1) {
      throw new BusinessException(UserErrorCode.USER_EMAIL_AMBIGUOUS, Map.of("email", email));
    }
    return users.stream().findFirst();
  }

  private static User newUser(String email, String name, String avatarUrl) {
    return User.builder().email(email).name(name).avatarUrl(avatarUrl).build();
  }

  @Override
  @Transactional(readOnly = true)
  public UserProfile getProfile(UUID userId) {
    return userRepository
        .findById(userId)
        .map(UserServiceImpl::toProfile)
        .orElseThrow(() -> notFound(userId));
  }

  @Override
  @Transactional(readOnly = true)
  public List<UserProfile> getProfiles(Collection<UUID> userIds) {
    return userRepository.findAllById(userIds).stream().map(UserServiceImpl::toProfile).toList();
  }

  @Override
  @Transactional
  public UserProfile updateProfile(UUID userId, String name, String jobRole) {
    User user = userRepository.findById(userId).orElseThrow(() -> notFound(userId));
    user.updateProfile(name, jobRole);
    return toProfile(user);
  }

  @Override
  @Transactional
  public void completeMobileOnboarding(UUID userId) {
    userRepository.findById(userId).orElseThrow(() -> notFound(userId)).completeMobileOnboarding();
  }

  @Override
  @Transactional(readOnly = true)
  public boolean isMobileOnboardingCompleted(UUID userId) {
    return userRepository
        .findById(userId)
        .orElseThrow(() -> notFound(userId))
        .isMobileOnboardingCompleted();
  }

  private static BusinessException notFound(UUID userId) {
    return new BusinessException(
        UserErrorCode.USER_NOT_FOUND, Map.of("user_id", userId.toString()));
  }

  private static UserProfile toProfile(User user) {
    return new UserProfile(
        user.getId(),
        user.getEmail(),
        user.getName(),
        user.getJobRole(),
        user.getAvatarUrl(),
        user.getCreatedAt(),
        user.getUpdatedAt());
  }
}
