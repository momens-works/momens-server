package works.momens.server.user.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import works.momens.server.common.persistence.JpaAuditingConfig;
import works.momens.server.common.test.AbstractPostgresIntegrationTest;
import works.momens.server.user.UserIdentityProvider;

/**
 * {@code user_identities} 스키마와 엔티티 매핑을 검증합니다.
 *
 * <p>애플리케이션에서 생성한 기본 키와 감사 필드가 정상적으로 저장되는지 확인하고, 마이그레이션에서 정의한 UNIQUE, CHECK, ON DELETE CASCADE 제약이
 * 실제로 동작하는지도 함께 검증합니다.
 *
 * <p>제약 위반을 검증할 때는 해당 시점에서 flush를 호출해 SQL이 즉시 실행되도록 해야 합니다. flush를 하지 않으면 테스트 종료 시 트랜잭션 정리 과정에서 예외가
 * 발생하면서, 테스트는 제약을 검증한 것처럼 보이지만 실제로는 아무것도 검증하지 못하게 됩니다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaAuditingConfig.class)
class UserIdentityRepositoryIntegrationTest extends AbstractPostgresIntegrationTest {

  @Autowired private UserIdentityRepository userIdentityRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private TestEntityManager entityManager;

  @Test
  @DisplayName("로그인 수단을 저장하면 애플리케이션에서 생성한 UUID v4 기본 키와 감사 필드가 저장된다")
  void savesIdentityWithGeneratedIdAndAuditFields() {
    UUID userId = persistUser("identity-save@momens.works");

    UserIdentity saved =
        userIdentityRepository.save(
            identity(userId, UserIdentityProvider.GOOGLE.value(), "sub-save"));
    entityManager.flush();
    entityManager.clear();

    UserIdentity found = userIdentityRepository.findById(saved.getId()).orElseThrow();

    assertThat(found.getId()).isNotNull();
    assertThat(found.getId().version()).as("UUID v4 PK").isEqualTo(4);
    assertThat(found.getCreatedAt()).isNotNull();
    assertThat(found.getUpdatedAt()).isNotNull();
  }

  @Test
  @DisplayName("저장한 로그인 수단을 provider와 provider_user_id로 조회할 수 있다")
  void readsBackByProviderAndProviderUserId() {
    UUID userId = persistUser("identity-read@momens.works");
    userIdentityRepository.save(identity(userId, UserIdentityProvider.GOOGLE.value(), "sub-read"));
    entityManager.flush();
    entityManager.clear();

    UserIdentity found =
        userIdentityRepository
            .findByProviderAndProviderUserId(UserIdentityProvider.GOOGLE.value(), "sub-read")
            .orElseThrow();

    assertThat(found.getUserId()).isEqualTo(userId);
    assertThat(found.getProvider()).isEqualTo(UserIdentityProvider.GOOGLE.value());
    assertThat(found.getProviderUserId()).isEqualTo("sub-read");
  }

  @Test
  @DisplayName("동일한 provider와 provider_user_id로 다시 저장하면 UNIQUE 제약 위반으로 실패한다")
  void rejectsDuplicateProviderUserId() {
    UUID first = persistUser("identity-dup-1@momens.works");
    UUID second = persistUser("identity-dup-2@momens.works");
    userIdentityRepository.saveAndFlush(
        identity(first, UserIdentityProvider.GOOGLE.value(), "sub-dup"));

    assertThatThrownBy(
            () ->
                userIdentityRepository.saveAndFlush(
                    identity(second, UserIdentityProvider.GOOGLE.value(), "sub-dup")))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("이미 연결된 로그인 수단을 다시 삽입하면 예외가 발생하지 않고 0건을 반환한다")
  void insertIgnoringConflictReturnsZeroOnDuplicate() {
    UUID first = persistUser("identity-conflict-1@momens.works");
    UUID second = persistUser("identity-conflict-2@momens.works");
    userIdentityRepository.saveAndFlush(
        identity(first, UserIdentityProvider.GOOGLE.value(), "sub-conflict"));

    int inserted =
        userIdentityRepository.insertIgnoringConflict(
            UUID.randomUUID(), second, UserIdentityProvider.GOOGLE.value(), "sub-conflict");

    assertThat(inserted).isZero();
    assertThat(
            userIdentityRepository
                .findByProviderAndProviderUserId(
                    UserIdentityProvider.GOOGLE.value(), "sub-conflict")
                .orElseThrow()
                .getUserId())
        .isEqualTo(first);
  }

  @Test
  @DisplayName("허용되지 않은 provider를 저장하면 CHECK 제약 위반으로 실패한다")
  void rejectsUnknownProvider() {
    UUID userId = persistUser("identity-check@momens.works");

    // provider를 문자열로 저장하므로 허용 목록에 없는 값도 엔티티에 설정할 수 있다.
    // 실제로 저장할 수 있는 값의 범위는 DB의 CHECK 제약이 제한한다.
    assertThatThrownBy(
            () -> userIdentityRepository.saveAndFlush(identity(userId, "apple", "sub-check")))
        .rootCause()
        .hasMessageContaining("user_identities_provider_check");
  }

  @Test
  @DisplayName("사용자를 삭제하면 해당 사용자의 로그인 수단도 함께 삭제된다")
  void cascadesDeleteFromUser() {
    UUID userId = persistUser("identity-cascade@momens.works");
    userIdentityRepository.save(
        identity(userId, UserIdentityProvider.GOOGLE.value(), "sub-cascade"));
    entityManager.flush();

    userRepository.deleteById(userId);
    entityManager.flush();
    // ON DELETE CASCADE는 DB에서 처리되기 때문에 영속성 컨텍스트에는 즉시 반영되지 않는다.
    // 실제 삭제 여부를 확인하려면 clear() 이후 다시 조회해야 한다.
    entityManager.clear();

    assertThat(
            userIdentityRepository.findByProviderAndProviderUserId(
                UserIdentityProvider.GOOGLE.value(), "sub-cascade"))
        .isEmpty();
  }

  private UUID persistUser(String email) {
    return userRepository.save(User.builder().email(email).name("이름").build()).getId();
  }

  private static UserIdentity identity(UUID userId, String provider, String providerUserId) {
    return UserIdentity.builder()
        .userId(userId)
        .provider(provider)
        .providerUserId(providerUserId)
        .build();
  }
}
