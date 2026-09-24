package works.momens.server.user;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * user 모듈의 public API.
 *
 * <p>다른 모듈(로그인 시 {@code auth})은 이 인터페이스로만 사용자를 다룹니다. 동기 반환값이 필요한 협력이라 application event가 아니라 public
 * API 직접 참조로 둡니다(docs/design/module-map.md). 엔티티·repository는 모듈 {@code internal}에 숨깁니다.
 */
public interface UserService {

  /**
   * 검증된 외부 신원 정보를 기준으로 사용자를 조회하거나 생성합니다.
   *
   * <p>먼저 로그인 수단으로 사용자를 조회하고, 연결된 사용자가 없으면 이메일로 기존 사용자를 조회합니다. 이메일로도 사용자를 찾지 못하면 새로운 사용자를 생성합니다.
   *
   * <p>이메일로 조회된 사용자에게 이미 다른 로그인 수단이 연결되어 있는 경우 {@link
   * UserErrorCode#USER_EMAIL_LINKED_TO_ANOTHER_IDENTITY}로 요청을 거부합니다(ADR-0016).
   *
   * <p>이메일 기반으로 기존 사용자를 연결하는 로직은 외부 신원과 이메일 검증이 완료된 경우에만 동작합니다.
   */
  UserProfile findOrCreateByIdentity(
      UserIdentityProvider provider,
      String providerUserId,
      String email,
      String name,
      String avatarUrl);

  /**
   * 로그인 수단 없이 이메일을 기준으로 사용자를 조회하거나 생성합니다.
   *
   * <p>dev 전용 토큰 발급처럼 외부 신원을 사용하지 않는 경로에서 호출됩니다. 이 메서드로 생성된 사용자에는 로그인 수단이 연결되지 않습니다(ADR-0016).
   */
  UserProfile findOrCreate(String email, String name, String avatarUrl);

  /**
   * email로 사용자 프로필을 조회합니다. 없으면 빈 {@link Optional}을 돌려줍니다. 읽기만 하므로 {@link #findOrCreate}와 달리 기존 프로필을
   * 바꾸지 않습니다.
   */
  Optional<UserProfile> findByEmail(String email);

  /** 사용자 프로필 조회. 없으면 {@link UserErrorCode#USER_NOT_FOUND}. */
  UserProfile getProfile(UUID userId);

  /**
   * 여러 사용자의 프로필을 한 번에 조회합니다. 멤버 목록처럼 다른 모듈이 가진 userId 목록에 프로필을 결합할 때 사용합니다(MOM-61).
   *
   * <p>없는 id는 에러 없이 결과에서 빠지고(반환 크기는 입력 이하), 반환 순서는 보장되지 않습니다. 순서가 필요하면 호출하는 쪽에서 정렬합니다.
   */
  List<UserProfile> getProfiles(Collection<UUID> userIds);

  /** 프로필 부분 수정. {@code null} 필드는 변경하지 않습니다. 없으면 {@link UserErrorCode#USER_NOT_FOUND}. */
  UserProfile updateProfile(UUID userId, String name, String jobRole);
}
