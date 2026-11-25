package works.momens.server.user.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
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
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class UserServiceConcurrencyIntegrationTest extends AbstractPostgresIntegrationTest {

  @Autowired private UserService userService;
  @Autowired private PlatformTransactionManager transactionManager;

  @Test
  void concurrentCompletionIsRecordedOnlyOnce() throws Exception {
    UserProfile user =
        userService.findOrCreate(
            "onboarding-" + UUID.randomUUID() + "@momens.works", "onboarding", null);

    CompletableFuture<Boolean> concurrent =
        new TransactionTemplate(transactionManager)
            .execute(status -> {
              assertThat(userService.completeMobileOnboarding(user.id())).isTrue();
              CompletableFuture<Boolean> other =
                  CompletableFuture.supplyAsync(
                      () -> userService.completeMobileOnboarding(user.id()));
              pause();
              return other;
            });

    assertThat(concurrent.get(10, TimeUnit.SECONDS)).isFalse();
  }

  private static void pause() {
    try {
      Thread.sleep(300);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
