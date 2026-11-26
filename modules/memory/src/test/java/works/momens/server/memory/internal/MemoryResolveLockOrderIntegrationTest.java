package works.momens.server.memory.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import works.momens.server.common.api.BusinessException;
import works.momens.server.common.persistence.JpaAuditingConfig;
import works.momens.server.common.test.AbstractPostgresIntegrationTest;
import works.momens.server.context.EntityRelationReader;
import works.momens.server.context.EntityRelationWriter;
import works.momens.server.memory.MemoryErrorCode;
import works.momens.server.memory.MemorySeedSql;
import works.momens.server.memory.MemoryWriter;
import works.momens.server.outbox.OutboxAppender;
import works.momens.server.workspace.label.LabelAllocator;
import works.momens.server.workspace.membership.WorkspaceMembershipReader;
import works.momens.server.workspace.membership.WorkspaceRole;

/**
 * 반대 방향 해결 요청의 잠금 순서를 검증합니다.
 *
 * <p>요청이 서로 다른 커넥션에서 커밋된 행을 잠가야 하므로 테스트 트랜잭션 없이 실행하고, 만든 행은 직접 지웁니다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, MemoryWriterImpl.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("메모리 해결 잠금 순서 통합 테스트")
class MemoryResolveLockOrderIntegrationTest extends AbstractPostgresIntegrationTest {

  @Autowired private MemoryWriter memoryWriter;
  @Autowired private TestEntityManager entityManager;
  @Autowired private PlatformTransactionManager transactionManager;

  @MockitoBean private WorkspaceMembershipReader workspaceMembershipReader;
  @MockitoBean private LabelAllocator labelAllocator;
  @MockitoBean private EntityRelationWriter relationWriter;
  @MockitoBean private EntityRelationReader relationReader;
  @MockitoBean private OutboxAppender outboxAppender;

  @Test
  @DisplayName("반대 방향 요청이 동시에 와도 같은 메모리부터 잠가 교착 없이 하나만 성공한다")
  void oppositeRequestsLockInSameOrder() throws Exception {
    TransactionTemplate tx = new TransactionTemplate(transactionManager);
    TransactionTemplate newTx = new TransactionTemplate(transactionManager);
    newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

    UUID workspaceId = tx.execute(status -> MemorySeedSql.insertWorkspace(entityManager));
    UUID userId = tx.execute(status -> MemorySeedSql.insertUser(entityManager));
    UUID first =
        tx.execute(status -> MemorySeedSql.insertMemory(entityManager, workspaceId, "ACTIVE"));
    UUID second =
        tx.execute(status -> MemorySeedSql.insertMemory(entityManager, workspaceId, "ACTIVE"));
    when(workspaceMembershipReader.roleOf(workspaceId, userId))
        .thenReturn(Optional.of(WorkspaceRole.MEMBER));
    when(relationReader.isLinked(any())).thenReturn(false);
    UUID lower = tx.execute(status -> lowerId(first, second));
    UUID higher = lower.equals(first) ? second : first;

    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      List<Future<?>> requests = new ArrayList<>();
      tx.executeWithoutResult(status -> {
        lockRow(lower);
        requests.add(executor.submit(() -> memoryWriter.resolve(lower, higher, userId)));
        requests.add(executor.submit(() -> memoryWriter.resolve(higher, lower, userId)));
        awaitLockWaiters(newTx, 2);

        // 두 요청이 모두 앞선 메모리에서 기다리면 뒤 메모리는 아무도 잠그지 않은 상태입니다.
        Boolean higherUnlocked = newTx.execute(probe -> tryLock(higher));
        assertThat(higherUnlocked).isTrue();
      });

      int succeeded = 0;
      int conflicted = 0;
      for (Future<?> request : requests) {
        try {
          request.get(10, TimeUnit.SECONDS);
          succeeded++;
        } catch (ExecutionException e) {
          assertThat(e.getCause()).isInstanceOf(BusinessException.class);
          assertThat(((BusinessException) e.getCause()).getErrorCode())
              .isEqualTo(MemoryErrorCode.MEMORY_INVALID_STATE);
          conflicted++;
        }
      }
      assertThat(succeeded).isEqualTo(1);
      assertThat(conflicted).isEqualTo(1);
    } finally {
      executor.shutdownNow();
      tx.executeWithoutResult(status -> cleanUp(workspaceId, userId));
    }
  }

  private UUID lowerId(UUID first, UUID second) {
    return (UUID)
        entityManager
            .getEntityManager()
            .createNativeQuery(
                "SELECT id FROM confirmed_memories WHERE id IN (?1, ?2) ORDER BY id LIMIT 1")
            .setParameter(1, first)
            .setParameter(2, second)
            .getSingleResult();
  }

  private void lockRow(UUID memoryId) {
    entityManager
        .getEntityManager()
        .createNativeQuery("SELECT id FROM confirmed_memories WHERE id = ?1 FOR UPDATE")
        .setParameter(1, memoryId)
        .getResultList();
  }

  /** 다른 트랜잭션이 잠근 행은 건너뛰므로, 결과가 비어 있으면 그 행이 잠겨 있다는 뜻입니다. */
  private boolean tryLock(UUID memoryId) {
    return !entityManager
        .getEntityManager()
        .createNativeQuery("SELECT id FROM confirmed_memories WHERE id = ?1 FOR UPDATE SKIP LOCKED")
        .setParameter(1, memoryId)
        .getResultList()
        .isEmpty();
  }

  private void awaitLockWaiters(TransactionTemplate newTx, int expected) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < deadline) {
      Number waiting =
          newTx.execute(status ->
              (Number)
                  entityManager
                      .getEntityManager()
                      .createNativeQuery(
                          """
                          SELECT count(*) FROM pg_stat_activity
                          WHERE datname = current_database() AND wait_event_type = 'Lock'
                            AND query LIKE '%confirmed_memories%FOR UPDATE%'
                          """)
                      .getSingleResult());
      if (waiting.intValue() >= expected) {
        return;
      }
      try {
        Thread.sleep(20);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("잠금 대기 확인 중 중단", e);
      }
    }
    throw new IllegalStateException("해결 요청이 잠금 대기에 들어가지 않았습니다");
  }

  private void cleanUp(UUID workspaceId, UUID userId) {
    entityManager
        .getEntityManager()
        .createNativeQuery("DELETE FROM confirmed_memories WHERE workspace_id = ?1")
        .setParameter(1, workspaceId)
        .executeUpdate();
    entityManager
        .getEntityManager()
        .createNativeQuery("DELETE FROM workspaces WHERE id = ?1")
        .setParameter(1, workspaceId)
        .executeUpdate();
    entityManager
        .getEntityManager()
        .createNativeQuery("DELETE FROM users WHERE id = ?1")
        .setParameter(1, userId)
        .executeUpdate();
  }
}
