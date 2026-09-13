package works.momens.server.workspace.core.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import works.momens.server.common.persistence.JpaAuditingConfig;
import works.momens.server.common.test.AbstractPostgresIntegrationTest;

/**
 * 워크스페이스 코어 영속성 검증.
 *
 * <p>실제 PostgreSQL(Testcontainers) + Flyway 마이그레이션 + {@code ddl-auto: validate} 위에서, 단일 UUID PK
 * 워크스페이스를 저장하고 감사 필드가 자동으로 채워지는지 확인합니다. 복합 PK 멤버십 검증은 access 하위 도메인의 {@code
 * WorkspaceMemberRepositoryIntegrationTest}가 담당합니다(MOM-70).
 */
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
