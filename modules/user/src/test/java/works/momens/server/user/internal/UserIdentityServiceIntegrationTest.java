package works.momens.server.user.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import works.momens.server.common.persistence.JpaAuditingConfig;
import works.momens.server.common.test.AbstractPostgresIntegrationTest;
import works.momens.server.user.UserProfile;
import works.momens.server.user.UserService;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, UserServiceImpl.class})
class UserIdentityServiceIntegrationTest extends AbstractPostgresIntegrationTest {

  @Autowired private UserService userService;
  @Autowired private UserRepository userRepository;
  @Autowired private UserIdentityRepository userIdentityRepository;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  @DisplayName("새 사용자를 생성하면 users 행과 로그인 수단이 함께 생성된다")
  void createsUserWithIdentity() {
    UserProfile created =
        userService.findOrCreateByIdentity(
            UserService.PROVIDER_GOOGLE, "sub-new", "new@momens.works", "홍길동", "https://a/x.png");

    assertThat(created.id()).isNotNull();
    assertThat(created.email()).isEqualTo("new@momens.works");
    assertThat(created.name()).isEqualTo("홍길동");
    assertThat(created.avatarUrl()).isEqualTo("https://a/x.png");
    assertThat(
            userIdentityRepository
                .findByProviderAndProviderUserId(UserService.PROVIDER_GOOGLE, "sub-new")
                .orElseThrow()
                .getUserId())
        .isEqualTo(created.id());
  }

  @Test
  @DisplayName("같은 sub으로 재로그인하면 사용자 ID와 직접 수정한 이름이 유지된다")
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  void keepsSameUserOnRelogin() {
    UserProfile first =
        userService.findOrCreateByIdentity(
            UserService.PROVIDER_GOOGLE, "sub-relogin", "relogin@momens.works", "이전", null);
    try {
      userService.updateProfile(first.id(), "직접 수정", null);

      UserProfile second =
          userService.findOrCreateByIdentity(
              UserService.PROVIDER_GOOGLE,
              "sub-relogin",
              "relogin@momens.works",
              "변경",
              "https://a/y.png");

      assertThat(second.id()).isEqualTo(first.id());
      assertThat(second.name()).isEqualTo("직접 수정");
      assertThat(second.avatarUrl()).isEqualTo("https://a/y.png");
      assertThat(userService.getProfile(first.id()).name()).isEqualTo("직접 수정");
    } finally {
      new TransactionTemplate(transactionManager)
          .executeWithoutResult(status -> userRepository.deleteById(first.id()));
    }
  }

  @Test
  @DisplayName("이메일이 변경되어도 동일 사용자의 수정한 이름을 유지하고 이메일을 갱신한다")
  void keepsSameUserWhenEmailChanges() {
    UserProfile first =
        userService.findOrCreateByIdentity(
            UserService.PROVIDER_GOOGLE, "sub-email", "before@momens.works", "홍길동", null);
    userService.updateProfile(first.id(), "직접 수정", null);

    UserProfile second =
        userService.findOrCreateByIdentity(
            UserService.PROVIDER_GOOGLE, "sub-email", "after@momens.works", "구글 이름", null);

    assertThat(second.id()).isEqualTo(first.id());
    assertThat(second.email()).isEqualTo("after@momens.works");
    assertThat(second.name()).isEqualTo("직접 수정");
    assertThat(userService.getProfile(first.id()).name()).isEqualTo("직접 수정");
  }

  @Test
  @DisplayName("같은 이메일의 기존 사용자가 있어도 그 사용자에 연결하지 않고 새 사용자를 생성한다")
  void createsNewUserEvenWhenEmailBelongsToExistingUser() {
    UserProfile existing = userService.findOrCreate("legacy@momens.works", "기존", null);

    UserProfile created =
        userService.findOrCreateByIdentity(
            UserService.PROVIDER_GOOGLE, "sub-legacy", "legacy@momens.works", "홍길동", null);

    assertThat(created.id()).isNotEqualTo(existing.id());
    assertThat(created.name()).isEqualTo("홍길동");
    assertThat(
            userIdentityRepository
                .findByProviderAndProviderUserId(UserService.PROVIDER_GOOGLE, "sub-legacy")
                .orElseThrow()
                .getUserId())
        .isEqualTo(created.id());
    assertThat(countUsersByEmail("legacy@momens.works")).isEqualTo(2);
  }

  @Test
  @DisplayName("다른 사용자가 동일한 이메일을 사용 중이어도 로그인한 사용자의 이메일을 갱신한다")
  void updatesEmailEvenWhenTakenByAnotherUser() {
    UserProfile taken = userService.findOrCreate("taken@momens.works", "선점자", null);
    UserProfile mine =
        userService.findOrCreateByIdentity(
            UserService.PROVIDER_GOOGLE, "sub-mine", "mine@momens.works", "홍길동", null);

    UserProfile after =
        userService.findOrCreateByIdentity(
            UserService.PROVIDER_GOOGLE, "sub-mine", "taken@momens.works", "홍길동", null);

    assertThat(after.id()).isEqualTo(mine.id());
    assertThat(after.email()).isEqualTo("taken@momens.works");
    assertThat(userRepository.findById(taken.id()).orElseThrow().getEmail())
        .isEqualTo("taken@momens.works");
  }

  /**
   * 동일 계정으로 최초 로그인 요청이 동시에 들어와도 users 테이블에 단일 행만 생성되는지 검증합니다.
   *
   * <p>각 요청이 독립적으로 커밋되어야 경합 상황을 재현할 수 있으므로 테스트 메서드는 비트랜잭션({@code NOT_SUPPORTED})으로 실행합니다. 테스트 데이터
   * 정리도 별도로 커밋하여 처리합니다.
   */
  @Test
  @DisplayName("동일 계정으로 최초 로그인 요청이 동시에 들어와도 users 행은 하나만 생성된다")
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  void createsSingleUserUnderConcurrentFirstLogin() throws Exception {
    int threads = 8;
    TransactionTemplate tx = new TransactionTemplate(transactionManager);
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      CountDownLatch start = new CountDownLatch(1);
      List<Future<UUID>> futures =
          IntStream.range(0, threads)
              .mapToObj(i ->
                  pool.submit(() -> {
                    start.await();
                    return tx.execute(status ->
                            userService.findOrCreateByIdentity(
                                UserService.PROVIDER_GOOGLE,
                                "sub-race",
                                "race@momens.works",
                                "홍길동",
                                null))
                        .id();
                  }))
              .toList();
      start.countDown();

      List<UUID> ids = new ArrayList<>();
      for (Future<UUID> future : futures) {
        ids.add(future.get());
      }

      assertThat(ids).hasSize(threads).containsOnly(ids.getFirst());
      assertThat(
              userIdentityRepository
                  .findByProviderAndProviderUserId(UserService.PROVIDER_GOOGLE, "sub-race")
                  .orElseThrow()
                  .getUserId())
          .isEqualTo(ids.getFirst());
      assertThat(countUsersByEmail("race@momens.works"))
          .as("로그인 수단을 연결하지 못한 요청은 자기가 만든 행을 지운다")
          .isEqualTo(1);
    } finally {
      pool.shutdownNow();
      jdbcTemplate.update("DELETE FROM users WHERE email = ?", "race@momens.works");
    }
  }

  private int countUsersByEmail(String email) {
    return jdbcTemplate.queryForObject(
        "SELECT count(*) FROM users WHERE email = ?", Integer.class, email);
  }
}
