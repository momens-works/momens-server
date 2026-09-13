package works.momens.server.workspace.membership.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import works.momens.server.common.persistence.JpaAuditingConfig;
import works.momens.server.common.test.AbstractPostgresIntegrationTest;
import works.momens.server.workspace.WorkspaceSeedSql;

/**
 * 멤버십 영속성 검증.
 *
 * <p>실제 PostgreSQL(Testcontainers) + Flyway 마이그레이션 + {@code ddl-auto: validate} 위에서, 복합 PK 멤버십을
 * 저장하고 감사 필드가 자동으로 채워지는지 확인합니다. 원래 워크스페이스 코어 검증과 한 테스트였고, access 하위 도메인 분리에 맞춰 멤버십 부분을 이 테스트로
 * 옮겼습니다(MOM-70).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaAuditingConfig.class)
class WorkspaceMemberRepositoryIntegrationTest extends AbstractPostgresIntegrationTest {

  @Autowired private WorkspaceMemberRepository workspaceMemberRepository;
  @Autowired private TestEntityManager entityManager;

  @Test
  void savesWorkspaceMemberWithCompositeKeyAndAuditFields() {
    UUID workspaceId = WorkspaceSeedSql.insertWorkspace(entityManager, "momens-ops");
    UUID userId = WorkspaceSeedSql.insertUser(entityManager, "owner@momens.works");

    workspaceMemberRepository.save(
        WorkspaceMember.builder().workspaceId(workspaceId).userId(userId).role("owner").build());
    entityManager.flush();
    entityManager.clear();

    WorkspaceMember found =
        workspaceMemberRepository
            .findById(new WorkspaceMemberId(workspaceId, userId))
            .orElseThrow();

    assertThat(found.getRole()).isEqualTo("owner");
    assertThat(found.getCreatedAt()).isNotNull();
    assertThat(found.getUpdatedAt()).isNotNull();
    assertThat(workspaceMemberRepository.findByWorkspaceIdOrderByCreatedAtAscUserIdAsc(workspaceId))
        .hasSize(1);
  }
}
