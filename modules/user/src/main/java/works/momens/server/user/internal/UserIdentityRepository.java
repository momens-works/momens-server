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
