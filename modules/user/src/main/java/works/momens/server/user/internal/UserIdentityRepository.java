package works.momens.server.user.internal;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface UserIdentityRepository extends JpaRepository<UserIdentity, UUID> {

  Optional<UserIdentity> findByProviderAndProviderUserId(String provider, String providerUserId);

  /**
   * 해당 사용자에게 인자로 전달된 로그인 수단을 제외하고, 다른 로그인 수단이 연결되어 있는지 확인합니다.
   *
   * <p>조회 시에는 반드시 인자로 전달된 로그인 수단을 제외합니다. 단순히 로그인 수단의 존재 여부만 확인하면, 동시에 들어온 다른 요청이 방금 연결한 동일한 로그인
   * 수단까지 포함하게 됩니다. 이 경우 동일한 사용자의 요청을 서로 다른 사용자 요청으로 잘못 판단할 수 있습니다.
   */
  @Query(
      """
      SELECT COUNT(identity) > 0 FROM UserIdentity identity
       WHERE identity.userId = :userId
         AND NOT (identity.provider = :provider
                  AND identity.providerUserId = :providerUserId)
      """)
  boolean existsOtherIdentity(
      @Param("userId") UUID userId,
      @Param("provider") String provider,
      @Param("providerUserId") String providerUserId);

  /**
   * 로그인 수단을 추가합니다. 이미 존재하는 경우에는 아무 작업도 수행하지 않습니다.
   *
   * <p>삽입된 행 수를 반환하며, 0이 반환되면 동시에 들어온 다른 요청이 먼저 해당 로그인 수단을 연결한 것입니다.
   */
  @Modifying
  @Query(
      value =
          """
          INSERT INTO user_identities (id, user_id, provider, provider_user_id)
          VALUES (:id, :userId, :provider, :providerUserId)
          ON CONFLICT (provider, provider_user_id) DO NOTHING
          """,
      nativeQuery = true)
  int insertIgnoringConflict(
      @Param("id") UUID id,
      @Param("userId") UUID userId,
      @Param("provider") String provider,
      @Param("providerUserId") String providerUserId);
}
