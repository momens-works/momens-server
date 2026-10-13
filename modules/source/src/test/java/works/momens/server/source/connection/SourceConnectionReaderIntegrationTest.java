package works.momens.server.source.connection;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import works.momens.server.common.test.AbstractPostgresIntegrationTest;
import works.momens.server.source.SourceConnectionDetail;
import works.momens.server.source.SourceConnectionReader;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(SourceConnectionReaderImpl.class)
class SourceConnectionReaderIntegrationTest extends AbstractPostgresIntegrationTest {

  @Autowired private SourceConnectionReader sourceConnectionReader;
  @Autowired private TestEntityManager entityManager;

  @Test
  @DisplayName("한 워크스페이스의 source 연결만 생성 시각 내림차순으로 조회한다")
  void listsConnectionsOfOneWorkspaceNewestFirst() {
    UUID workspaceId = insertWorkspace();
    UUID otherWorkspaceId = insertWorkspace();
    insertConnection(workspaceId, "GITHUB", "ext-1", Instant.parse("2026-08-01T00:00:00Z"));
    insertConnection(workspaceId, "SLACK", "ext-2", Instant.parse("2026-08-03T00:00:00Z"));
    insertConnection(otherWorkspaceId, "NOTION", "ext-3", Instant.parse("2026-08-02T00:00:00Z"));

    List<SourceConnectionDetail> details =
        sourceConnectionReader.listDetailsByWorkspaceId(workspaceId);

    assertThat(details)
        .extracting(SourceConnectionDetail::sourceType)
        .containsExactly("SLACK", "GITHUB");
  }

  @Test
  @DisplayName("레거시 응답에 포함되는 모든 컬럼을 채운다")
  void mapsEveryColumnTheLegacyResponseCarries() {
    UUID workspaceId = insertWorkspace();
    UUID connectionId =
        insertConnection(workspaceId, "FIGMA", "ext-9", Instant.parse("2026-08-05T00:00:00Z"));

    SourceConnectionDetail detail =
        sourceConnectionReader.listDetailsByWorkspaceId(workspaceId).getFirst();

    assertThat(detail.id()).isEqualTo(connectionId);
    assertThat(detail.workspaceId()).isEqualTo(workspaceId);
    assertThat(detail.status()).isEqualTo("ACTIVE");
    assertThat(detail.externalWorkspaceId()).isEqualTo("ext-9");
    assertThat(detail.externalWorkspaceName()).isEqualTo("설계 공유 파일");
    assertThat(detail.capturesReadCount()).isZero();
    assertThat(detail.candidatesExtractedCount()).isZero();
    assertThat(detail.metadata()).containsEntry("team_id", "T-1");
  }

  @Test
  @DisplayName("source 연결이 없는 워크스페이스는 빈 목록을 반환한다")
  void returnsEmptyListWhenWorkspaceHasNoConnection() {
    assertThat(sourceConnectionReader.listDetailsByWorkspaceId(insertWorkspace())).isEmpty();
  }

  @Test
  @DisplayName("같은 외부 계정이 두 번 연결되어도 두 행을 모두 유지한다")
  void keepsBothRowsWhenTheSameExternalWorkspaceIsConnectedTwice() {
    UUID workspaceId = insertWorkspace();
    insertConnection(workspaceId, "GITHUB", "ext-same", Instant.parse("2026-08-01T00:00:00Z"));
    insertConnection(workspaceId, "GITHUB", "ext-same", Instant.parse("2026-08-02T00:00:00Z"));

    assertThat(sourceConnectionReader.listDetailsByWorkspaceId(workspaceId)).hasSize(2);
  }

  private UUID insertWorkspace() {
    UUID workspaceId = UUID.randomUUID();
    entityManager
        .getEntityManager()
        .createNativeQuery(
            "INSERT INTO workspaces (id, name, slug, created_at, updated_at)"
                + " VALUES (:id, :name, :slug, NOW(), NOW())")
        .setParameter("id", workspaceId)
        .setParameter("name", "모먼스")
        .setParameter("slug", "ws-" + workspaceId.toString().substring(0, 8))
        .executeUpdate();
    return workspaceId;
  }

  private UUID insertConnection(
      UUID workspaceId, String sourceType, String externalWorkspaceId, Instant createdAt) {
    UUID id = UUID.randomUUID();
    entityManager
        .getEntityManager()
        .createNativeQuery(
            "INSERT INTO source_connections (id, workspace_id, source_type, status,"
                + " external_workspace_id, external_workspace_name, metadata, created_at, updated_at)"
                + " VALUES (:id, :workspaceId, :sourceType, 'ACTIVE', :externalWorkspaceId,"
                + " :externalWorkspaceName, CAST(:metadata AS jsonb), :createdAt, :createdAt)")
        .setParameter("id", id)
        .setParameter("workspaceId", workspaceId)
        .setParameter("sourceType", sourceType)
        .setParameter("externalWorkspaceId", externalWorkspaceId)
        .setParameter("externalWorkspaceName", "설계 공유 파일")
        .setParameter("metadata", "{\"team_id\": \"T-1\"}")
        .setParameter("createdAt", createdAt)
        .executeUpdate();
    return id;
  }
}
