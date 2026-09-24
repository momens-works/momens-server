package works.momens.server.user.internal;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import works.momens.server.common.api.BusinessException;
import works.momens.server.user.UserErrorCode;
import works.momens.server.user.UserIdentityProvider;
import works.momens.server.user.UserProfile;
import works.momens.server.user.UserService;

@Service
@RequiredArgsConstructor
@Slf4j
class UserServiceImpl implements UserService {

  private final UserRepository userRepository;
  private final UserIdentityRepository userIdentityRepository;

  @Override
  @Transactional
  public UserProfile findOrCreateByIdentity(
      UserIdentityProvider identityProvider,
      String providerUserId,
      String email,
      String name,
      String avatarUrl) {
    String provider = identityProvider.value();
    Optional<UserIdentity> identity =
        userIdentityRepository.findByProviderAndProviderUserId(provider, providerUserId);
    if (identity.isPresent()) {
      return refreshProfile(identity.get().getUserId(), email, name, avatarUrl);
    }

    Optional<User> byEmail = userRepository.findByEmail(email);
    if (byEmail.isPresent()) {
      UUID userId = byEmail.get().getId();
      // 이메일이 다른 계정으로 재할당된 경우, 이메일 조회 시 이전 사용자의 행이 반환될 수 있다.
      // 해당 사용자에게 이미 다른 로그인 수단이 연결되어 있다면
      // 다른 사람의 계정에 로그인 수단을 연결하는 상황이 되므로 요청을 거부한다.
      // 이관 기간에는 email UNIQUE 제약이 유지되므로 동일 이메일로 신규 사용자 생성도 불가능하다.
      if (userIdentityRepository.existsOtherIdentity(userId, provider, providerUserId)) {
        log.warn(
            "이메일로 조회한 사용자에게 이미 다른 로그인 수단이 연결되어 있어 로그인을 거부했습니다. userId={} provider={}",
            userId,
            provider);
        throw new BusinessException(UserErrorCode.USER_EMAIL_LINKED_TO_ANOTHER_IDENTITY);
      }
      // 삽입 결과가 0건이면 동시에 들어온 다른 요청이 먼저 로그인 수단을 연결한 것이다.
      // 이메일 변경 시점에는 동일한 Google 계정에서 발생한 요청이 서로 다른 이메일로 들어올 수 있고,
      // 이 경우 각 요청이 서로 다른 users 행을 조회하게 된다. 따라서 이메일로 조회한 사용자가 아니라
      // 실제로 로그인 수단이 연결된 사용자를 다시 조회해 로그인 흐름을 이어간다.
      int linked =
          userIdentityRepository.insertIgnoringConflict(
              UUID.randomUUID(), userId, provider, providerUserId);
      UUID linkedUserId = linked == 0 ? winnerUserId(provider, providerUserId) : userId;
      return refreshProfile(linkedUserId, email, name, avatarUrl);
    }

    // 같은 이메일로 최초 로그인 요청이 동시에 들어오면 먼저 생성된 행과 충돌한 나머지 삽입은 아무 작업도 하지 않습니다.
    // 이어지는 조회에서 먼저 생성된 행을 읽으므로 users 행은 하나만 생성됩니다.
    // 해당 동작은 users.email의 UNIQUE 제약이 유지되는 동안 성립합니다.
    //
    // UNIQUE 제약을 제거하면 요청마다 users 행이 생성되고, 로그인 수단 삽입에 성공한 요청의 행만 user_identities에 연결됩니다.
    // 로그인 수단 삽입에 실패한 요청은 삽입 건수 0을 확인한 뒤 성공한 요청의 사용자를 다시 조회하므로,
    // 모든 요청이 같은 사용자를 반환하는 동작은 유지됩니다.
    // 연결되지 않은 users 행이 남는 결과는 ADR-0016 결과 절에서 허용했습니다.
    userRepository.insertIgnoringConflict(UUID.randomUUID(), email, name, avatarUrl);
    User created = userRepository.findByEmail(email).orElseThrow();
    int inserted =
        userIdentityRepository.insertIgnoringConflict(
            UUID.randomUUID(), created.getId(), provider, providerUserId);
    if (inserted == 0) {
      return refreshProfile(winnerUserId(provider, providerUserId), email, name, avatarUrl);
    }
    return toProfile(created);
  }

  private UUID winnerUserId(String provider, String providerUserId) {
    return userIdentityRepository
        .findByProviderAndProviderUserId(provider, providerUserId)
        .orElseThrow()
        .getUserId();
  }

  /** 로그인 시점의 최신 프로필을 반영합니다. 이메일은 충돌 시 건너뛰므로 별도 쿼리로 처리합니다. */
  private UserProfile refreshProfile(UUID userId, String email, String name, String avatarUrl) {
    User user = userRepository.findById(userId).orElseThrow(() -> notFound(userId));
    user.refreshLoginProfile(name, avatarUrl);
    if (userRepository.updateEmailIfUnused(userId, email) == 0) {
      // 개인정보(email)는 로그에 남기지 않으며, 식별은 userId로만 수행한다. (code-conventions 규칙)
      log.warn("로그인 이메일을 갱신하지 않았습니다. 해당 이메일은 이미 다른 사용자가 사용 중입니다. userId={}", userId);
    }
    return toProfile(userRepository.findById(userId).orElseThrow());
  }

  @Override
  @Transactional
  public UserProfile findOrCreate(String email, String name, String avatarUrl) {
    // 조회보다 삽입을 먼저 시도합니다.
    // 조회를 먼저 수행하면 같은 신규 이메일로 동시에 들어온 두 요청이 모두 삽입을 시도해 한쪽 요청이 실패할 수 있습니다.
    //
    // 이름과 프로필 이미지는 엔티티 변경으로 갱신합니다.
    // 삽입이 충돌하면 SQL은 아무 작업도 하지 않으므로 기존 행의 값은 변경되지 않습니다.
    // 엔티티의 값이 기존 값과 같으면 UPDATE 쿼리도 실행되지 않습니다.
    //
    // @LastModifiedDate는 flush 시점에 updated_at을 갱신합니다.
    // flush하지 않으면 반환하는 프로필에 갱신 전 updated_at이 포함되므로 명시적으로 호출합니다.
    userRepository.insertIgnoringConflict(UUID.randomUUID(), email, name, avatarUrl);
    User user = userRepository.findByEmail(email).orElseThrow();
    user.refreshLoginProfile(name, avatarUrl);
    userRepository.flush();
    return toProfile(user);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<UserProfile> findByEmail(String email) {
    return userRepository.findByEmail(email).map(UserServiceImpl::toProfile);
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
