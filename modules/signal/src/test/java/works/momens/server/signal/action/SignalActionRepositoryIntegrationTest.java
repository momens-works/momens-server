package works.momens.server.signal.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
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

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaAuditingConfig.class)
class SignalActionRepositoryIntegrationTest extends AbstractPostgresIntegrationTest {

  private static final UUID WORKSPACE_ID = UUID.randomUUID();
  private static final UUID SIGNAL_ID = UUID.randomUUID();
  private static final UUID USER_ID = UUID.randomUUID();

  @Autowired private SignalActionRepository signalActionRepository;
  @Autowired private TestEntityManager entityManager;

  @Test
  @DisplayName("저장한 값을 그대로 조회한다")
  void savesAndFindsBySignalId() {
    UUID taskId = UUID.randomUUID();
    signalActionRepository.save(
        SignalAction.builder()
            .workspaceId(WORKSPACE_ID)
            .signalId(SIGNAL_ID)
            .actionType("convert_to_task")
            .resultTaskId(taskId)
            .processedByUserId(USER_ID)
            .build());
    entityManager.flush();
    entityManager.clear();

    Optional<SignalAction> found = signalActionRepository.findBySignalId(SIGNAL_ID);

    assertThat(found).isPresent();
    assertThat(found.get().getActionType()).isEqualTo("convert_to_task");
    assertThat(found.get().getResultTaskId()).isEqualTo(taskId);
    assertThat(found.get().getWorkspaceId()).isEqualTo(WORKSPACE_ID);
  }

  @Test
  @DisplayName("같은 signal_id 두 번째 저장은 UNIQUE 제약 위반으로 실패한다")
  void rejectsDuplicateSignalId() {
    signalActionRepository.saveAndFlush(
        SignalAction.builder()
            .workspaceId(WORKSPACE_ID)
            .signalId(SIGNAL_ID)
            .actionType("dismiss")
            .processedByUserId(USER_ID)
            .build());

    assertThatThrownBy(
            () ->
                signalActionRepository.saveAndFlush(
                    SignalAction.builder()
                        .workspaceId(WORKSPACE_ID)
                        .signalId(SIGNAL_ID)
                        .actionType("convert_to_task")
                        .processedByUserId(USER_ID)
                        .build()))
        .isInstanceOf(DataIntegrityViolationException.class);
  }
}
