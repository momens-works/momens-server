package works.momens.server.workspace.core.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import works.momens.server.common.persistence.JpaAuditingConfig;
import works.momens.server.common.test.AbstractPostgresIntegrationTest;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaAuditingConfig.class)
class WorkspaceRepositoryIntegrationTest extends AbstractPostgresIntegrationTest {

  @Autowired private WorkspaceRepository workspaceRepository;

  @Test
  void savesWorkspaceWithGeneratedIdAndAuditFields() {
    Workspace saved =
        workspaceRepository.save(Workspace.builder().name("모멘스").slug("momens").build());

    assertThat(saved.getId()).isNotNull();
    assertThat(saved.getId().version()).as("UUID v4 PK").isEqualTo(4);
    assertThat(saved.getCreatedAt()).isNotNull();
    assertThat(saved.getUpdatedAt()).isNotNull();
  }
}
