package works.momens.server.user.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import works.momens.server.common.api.BusinessException;
import works.momens.server.common.persistence.JpaAuditingConfig;
import works.momens.server.common.test.AbstractPostgresIntegrationTest;
import works.momens.server.user.UserErrorCode;
import works.momens.server.user.UserIdentityProvider;
import works.momens.server.user.UserProfile;
import works.momens.server.user.UserService;

/**
 * 외부 신원 정보를 기준으로 사용자 조회 및 생성 로직을 검증합니다.
 *
 * <p>로그인 수단으로 사용자를 조회하는 경로, 이메일로 기존 사용자를 조회한 뒤 로그인 수단을 연결하는 경로, 그리고 새로운 사용자를 생성하는 경로가 각각 정상적으로
 * 동작하는지 확인합니다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, UserServiceImpl.class})
class UserIdentityServiceIntegrationTest extends AbstractPostgresIntegrationTest {

  @Autowired private UserService userService;
  @Autowired private UserRepository userRepository;
  @Autowired private UserIdentityRepository userIdentityRepository;
  @Autowired private PlatformTransactionManager transactionManager;

  @Test
  @DisplayName("새 사용자를 생성하면 users 행과 로그인 수단이 함께 생성된다")
  void createsUserWithIdentity() {
    UserProfile created =
        userService.findOrCreateByIdentity(
            UserIdentityProvider.GOOGLE, "sub-new", "new@momens.works", "홍길동", "https://a/x.png");

    assertThat(created.id()).isNotNull();
    assertThat(created.email()).isEqualTo("new@momens.works");
    assertThat(created.name()).isEqualTo("홍길동");
    assertThat(created.avatarUrl()).isEqualTo("https://a/x.png");
    assertThat(
            userIdentityRepository
                .findByProviderAndProviderUserId(UserIdentityProvider.GOOGLE.value(), "sub-new")
                .orElseThrow()
                .getUserId())
        .isEqualTo(created.id());
  }

  @Test
  @DisplayName("같은 sub으로 재로그인하면 동일한 users.id가 유지된다")
  void keepsSameUserOnRelogin() {
    UserProfile first =
        userService.findOrCreateByIdentity(
            UserIdentityProvider.GOOGLE, "sub-relogin", "relogin@momens.works", "이전", null);

    UserProfile second =
        userService.findOrCreateByIdentity(
            UserIdentityProvider.GOOGLE,
            "sub-relogin",
            "relogin@momens.works",
            "변경",
            "https://a/y.png");

    assertThat(second.id()).isEqualTo(first.id());
    assertThat(second.name()).isEqualTo("변경");
    assertThat(second.avatarUrl()).isEqualTo("https://a/y.png");
  }

  @Test
  @DisplayName("이메일이 변경되더라도 sub이 동일하면 동일 사용자로 식별하고 이메일을 갱신한다")
  void keepsSameUserWhenEmailChanges() {
    UserProfile first =
        userService.findOrCreateByIdentity(
            UserIdentityProvider.GOOGLE, "sub-email", "before@momens.works", "홍길동", null);

    UserProfile second =
        userService.findOrCreateByIdentity(
            UserIdentityProvider.GOOGLE, "sub-email", "after@momens.works", "홍길동", null);

    assertThat(second.id()).isEqualTo(first.id());
    assertThat(second.email()).isEqualTo("after@momens.works");
  }

  @Test
  @DisplayName("로그인 수단이 없는 기존 사용자는 이메일로 조회한 뒤 로그인 수단을 연결한다")
  void linksIdentityToExistingUserByEmail() {
    UserProfile existing = userService.findOrCreate("legacy@momens.works", "기존", null);

    UserProfile linked =
        userService.findOrCreateByIdentity(
            UserIdentityProvider.GOOGLE, "sub-legacy", "legacy@momens.works", "홍길동", null);

    assertThat(linked.id()).isEqualTo(existing.id());
    assertThat(linked.name()).isEqualTo("홍길동");
    assertThat(
            userIdentityRepository
                .findByProviderAndProviderUserId(UserIdentityProvider.GOOGLE.value(), "sub-legacy")
                .orElseThrow()
                .getUserId())
        .isEqualTo(existing.id());
  }

  @Test
  @DisplayName("이메일로 조회된 사용자에게 이미 다른 로그인 수단이 연결되어 있으면 로그인을 거부한다")
  void rejectsWhenEmailBelongsToAnotherIdentity() {
    userService.findOrCreateByIdentity(
        UserIdentityProvider.GOOGLE, "sub-owner", "shared@momens.works", "이전주인", null);

    assertThatThrownBy(
            () ->
                userService.findOrCreateByIdentity(
                    UserIdentityProvider.GOOGLE,
                    "sub-newcomer",
                    "shared@momens.works",
                    "새주인",
                    null))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(UserErrorCode.USER_EMAIL_LINKED_TO_ANOTHER_IDENTITY);
  }

  @Test
  @DisplayName("다른 사용자가 동일한 이메일을 사용 중이면 이메일 갱신은 수행하지 않는다")
  void skipsEmailUpdateWhenTakenByAnotherUser() {
    UserProfile taken = userService.findOrCreate("taken@momens.works", "선점자", null);
    UserProfile mine =
        userService.findOrCreateByIdentity(
            UserIdentityProvider.GOOGLE, "sub-mine", "mine@momens.works", "홍길동", null);

    UserProfile after =
        userService.findOrCreateByIdentity(
            UserIdentityProvider.GOOGLE, "sub-mine", "taken@momens.works", "홍길동", null);

    assertThat(after.id()).isEqualTo(mine.id());
    assertThat(after.email()).as("이메일 갱신을 건너뛰면 기존 이메일 값이 유지된다").isEqualTo("mine@momens.works");
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
              .mapToObj(
                  i ->
                      pool.submit(
                          () -> {
                            start.await();
                            return tx.execute(
                                    status ->
                                        userService.findOrCreateByIdentity(
                                            UserIdentityProvider.GOOGLE,
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
                  .findByProviderAndProviderUserId(UserIdentityProvider.GOOGLE.value(), "sub-race")
                  .orElseThrow()
                  .getUserId())
          .isEqualTo(ids.getFirst());
    } finally {
      pool.shutdownNow();
      tx.executeWithoutResult(
          status ->
              userRepository.findByEmail("race@momens.works").ifPresent(userRepository::delete));
    }
  }

  /**
   * 로그인 수단이 없는 기존 사용자에게 로그인 요청이 잇따라 들어와도 모두 동일한 사용자로 이어지는지 검증합니다.
   *
   * <p>경합 상황을 재현하려면 두 번째 요청이 첫 번째 요청의 트랜잭션이 진행 중인 동안 도착해야 합니다. 두 요청을 동시에 시작하면 로그인 수단 조회와 중복 확인이 나란히
   * 수행되어 의도한 경합이 발생하지 않습니다. 따라서 요청 시작 시점을 조금씩 지연시키며 반복 실행합니다.
   */
  @Test
  @DisplayName("전환 대상 사용자에게 로그인 요청이 잇따라 들어와도 모두 동일한 사용자로 이어진다")
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  void linksIdentityOnceUnderStaggeredFallbackLogin() throws Exception {
    int rounds = 120;
    TransactionTemplate tx = new TransactionTemplate(transactionManager);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      for (int round = 0; round < rounds; round++) {
        String email = "fallback-race-" + round + "@momens.works";
        String providerUserId = "sub-fallback-race-" + round;
        long delayNanos = (round % 80) * 250_000L;
        UUID existingId = userService.findOrCreate(email, "기존", null).id();
        try {
          CountDownLatch start = new CountDownLatch(1);
          List<Future<UUID>> futures =
              IntStream.range(0, 2)
                  .mapToObj(
                      i ->
                          pool.submit(
                              () -> {
                                start.await();
                                if (i > 0) {
                                  LockSupport.parkNanos(delayNanos);
                                }
                                return tx.execute(
                                        status ->
                                            userService.findOrCreateByIdentity(
                                                UserIdentityProvider.GOOGLE,
                                                providerUserId,
                                                email,
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

          assertThat(ids).as("round=%d delayNanos=%d", round, delayNanos).containsOnly(existingId);
        } finally {
          tx.executeWithoutResult(
              status -> userRepository.findByEmail(email).ifPresent(userRepository::delete));
        }
      }
    } finally {
      pool.shutdownNow();
    }
  }
}
