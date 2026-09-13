package works.momens.server.workspace.label.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import works.momens.server.common.persistence.JpaAuditingConfig;
import works.momens.server.common.test.AbstractPostgresIntegrationTest;
import works.momens.server.workspace.WorkspaceSeedSql;
import works.momens.server.workspace.label.LabelAllocator;

/**
 * 라벨 발급 동작 검증.
 *
 * <p>PostgreSQL(Testcontainers)에 Flyway 마이그레이션을 적용한 환경에서 세 가지를 검증합니다. 워크스페이스마다 라벨 번호가 1부터 독립적으로
 * 증가하는지, 동일한 워크스페이스 안에서도 접두사별로 번호가 독립적으로 증가하는지, 동일한 워크스페이스에서 라벨을 동시에 발급해도 번호가 중복되지 않는지 확인합니다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, LabelAllocatorImpl.class})
class LabelAllocatorIntegrationTest extends AbstractPostgresIntegrationTest {

  @Autowired private LabelAllocator labelAllocator;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private TestEntityManager entityManager;

  @Test
  void allocatesSequentialMomLabelsPerWorkspace() {
    UUID workspaceId = WorkspaceSeedSql.insertWorkspace(entityManager, "momens-seq");

    assertThat(labelAllocator.allocateMomLabel(workspaceId)).isEqualTo("MOM-0001");
    assertThat(labelAllocator.allocateMomLabel(workspaceId)).isEqualTo("MOM-0002");
    assertThat(labelAllocator.allocateMomLabel(workspaceId)).isEqualTo("MOM-0003");
  }

  @Test
  void keepsSequencesIndependentAcrossWorkspaces() {
    UUID first = WorkspaceSeedSql.insertWorkspace(entityManager, "momens-a");
    UUID second = WorkspaceSeedSql.insertWorkspace(entityManager, "momens-b");

    assertThat(labelAllocator.allocateMomLabel(first)).isEqualTo("MOM-0001");
    assertThat(labelAllocator.allocateMomLabel(second)).isEqualTo("MOM-0001");
    assertThat(labelAllocator.allocateMomLabel(first)).isEqualTo("MOM-0002");
  }

  @Test
  void keepsSequencesIndependentAcrossPrefixes() {
    UUID workspaceId = WorkspaceSeedSql.insertWorkspace(entityManager, "momens-prefix");

    assertThat(labelAllocator.allocateProjectLabel(workspaceId)).isEqualTo("PRJ-0001");
    assertThat(labelAllocator.allocateMomLabel(workspaceId)).isEqualTo("MOM-0001");
    assertThat(labelAllocator.allocateProjectLabel(workspaceId)).isEqualTo("PRJ-0002");
  }

  /**
   * 같은 workspace에 동시 발급이 들어와도 번호가 겹치지 않는지 확인합니다. 발급이 독립 커밋되어야 경합이 재현되므로 테스트 메서드는
   * 비트랜잭션(NOT_SUPPORTED)으로 두고, workspace 준비와 정리는 직접 커밋합니다. N개 발급 결과가 전부 distinct하고 {@code
   * MOM-0001..MOM-00NN}과 정확히 일치하면 중복도 누락도 없는 것입니다.
   */
  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  void allocatesWithoutDuplicatesUnderConcurrency() throws Exception {
    int threads = 20;
    TransactionTemplate tx = new TransactionTemplate(transactionManager);
    UUID workspaceId =
        tx.execute(status -> WorkspaceSeedSql.insertWorkspace(entityManager, "momens-concurrent"));

    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      CountDownLatch start = new CountDownLatch(1);
      List<Future<String>> futures =
          IntStream.range(0, threads)
              .mapToObj(
                  i ->
                      pool.submit(
                          () -> {
                            start.await();
                            // 발급은 MANDATORY라 호출자 트랜잭션이 있어야 한다. 각 스레드가 자기
                            // 트랜잭션을 열어 커밋하므로 실제로 같은 행을 동시에 두드리는 상황이 된다.
                            return tx.execute(
                                status -> labelAllocator.allocateMomLabel(workspaceId));
                          }))
              .toList();
      start.countDown();

      List<String> labels = new ArrayList<>();
      for (Future<String> future : futures) {
        labels.add(future.get());
      }

      List<String> expected =
          IntStream.rangeClosed(1, threads)
              .mapToObj(n -> String.format("MOM-%04d", n))
              .sorted()
              .toList();
      assertThat(labels).hasSize(threads).doesNotHaveDuplicates();
      assertThat(labels.stream().sorted().toList()).isEqualTo(expected);
    } finally {
      pool.shutdownNow();
      tx.executeWithoutResult(
          status ->
              entityManager
                  .getEntityManager()
                  .createNativeQuery("DELETE FROM workspaces WHERE id = ?1")
                  .setParameter(1, workspaceId)
                  .executeUpdate());
    }
  }
}
