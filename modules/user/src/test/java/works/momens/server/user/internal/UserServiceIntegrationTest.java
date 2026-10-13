package works.momens.server.user.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.transaction.TestTransaction;
import works.momens.server.common.api.BusinessException;
import works.momens.server.common.persistence.JpaAuditingConfig;
import works.momens.server.common.test.AbstractPostgresIntegrationTest;
import works.momens.server.user.UserErrorCode;
import works.momens.server.user.UserProfile;
import works.momens.server.user.UserService;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, UserServiceImpl.class})
class UserServiceIntegrationTest extends AbstractPostgresIntegrationTest {

  @Autowired private UserService userService;
  @Autowired private UserRepository userRepository;

  @Test
  void findOrCreateCreatesNewUser() {
    UserProfile created = userService.findOrCreate("new@momens.works", "홍길동", "https://a/x.png");

    assertThat(created.id()).isNotNull();
    assertThat(created.email()).isEqualTo("new@momens.works");
    assertThat(created.name()).isEqualTo("홍길동");
    assertThat(created.avatarUrl()).isEqualTo("https://a/x.png");
    assertThat(created.jobRole()).isNull();
  }

  @Test
  void findOrCreateReusesExistingUserAndRefreshesProfile() {
    UserProfile first = userService.findOrCreate("same@momens.works", "이전", null);

    // 로그인 요청마다 별도의 트랜잭션을 사용합니다.
    // 첫 번째 호출을 커밋한 뒤 새 트랜잭션에서 다시 호출해 테스트 트랜잭션의 1차 캐시가 아닌
    // 데이터베이스에 저장된 행을 기준으로 동작하는지 검증합니다.
    TestTransaction.flagForCommit();
    TestTransaction.end();
    TestTransaction.start();

    UserProfile second = userService.findOrCreate("same@momens.works", "변경", "https://a/y.png");

    assertThat(second.id()).isEqualTo(first.id());
    assertThat(second.name()).isEqualTo("변경");
    assertThat(second.avatarUrl()).isEqualTo("https://a/y.png");

    userRepository.deleteById(second.id());
    TestTransaction.flagForCommit();
    TestTransaction.end();
    TestTransaction.start();
  }

  @Test
  void updateProfileChangesProvidedFieldsOnly() {
    UUID id = userService.findOrCreate("edit@momens.works", "원래", null).id();

    UserProfile updated = userService.updateProfile(id, "새이름", "Engineer");
    assertThat(updated.name()).isEqualTo("새이름");
    assertThat(updated.jobRole()).isEqualTo("Engineer");

    UserProfile nameKept = userService.updateProfile(id, null, "Designer");
    assertThat(nameKept.name()).isEqualTo("새이름");
    assertThat(nameKept.jobRole()).isEqualTo("Designer");
  }

  @Test
  void getProfilesReturnsOnlyExistingUsers() {
    UUID kim = userService.findOrCreate("kim@momens.works", "김민지", "https://a/kim.png").id();
    UUID lee = userService.findOrCreate("lee@momens.works", "이서준", null).id();

    List<UserProfile> profiles = userService.getProfiles(List.of(kim, lee, UUID.randomUUID()));

    // 없는 id는 에러 없이 빠진다(반환 크기는 입력 이하, CrudRepository.findAllById 계약).
    assertThat(profiles)
        .extracting(UserProfile::id, UserProfile::name)
        .containsExactlyInAnyOrder(tuple(kim, "김민지"), tuple(lee, "이서준"));
  }

  @Test
  void getProfileThrowsWhenMissing() {
    assertThatThrownBy(() -> userService.getProfile(UUID.randomUUID()))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(UserErrorCode.USER_NOT_FOUND);
  }
}
