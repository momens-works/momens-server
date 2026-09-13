package works.momens.server.source.ref;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import works.momens.server.common.persistence.JpaAuditingConfig;
import works.momens.server.common.test.AbstractPostgresIntegrationTest;
import works.momens.server.source.SourceRefWriter;
import works.momens.server.workspace.WorkspaceSeedSql;

/**
 * 사용자가 붙여넣은 링크가 PostgreSQL에 어떤 값으로 저장되는지 검증합니다.
 *
 * <p>저장값 자체가 레거시와 일치해야 하는 계약이며 응답에는 드러나지 않으므로 각 컬럼을 직접 조회해 확인합니다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, SourceRefWriterImpl.class})
class SourceRefWriterIntegrationTest extends AbstractPostgresIntegrationTest {

  @Autowired private SourceRefWriter sourceRefWriter;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private TestEntityManager entityManager;

  @Test
  @DisplayName("붙여넣은 링크를 레거시와 같은 컬럼 값으로 저장한다")
  void storesManualLinkWithLegacyColumnValues() {
    UUID workspaceId = insertWorkspace();

    UUID sourceRefId =
        sourceRefWriter.createManualLink(
            new SourceRefWriter.NewManualLink(
                workspaceId, "notion", "  https://www.notion.so/momens/scrum  ", "  스크럼 노트  "));

    Map<String, Object> row = row(sourceRefId);
    assertThat(row.get("workspace_id")).isEqualTo(workspaceId);
    assertThat(row.get("source_type")).isEqualTo("NOTION");
    assertThat(row.get("source_object_type")).isEqualTo("link");
    assertThat(row.get("source_object_id")).isEqualTo("https://www.notion.so/momens/scrum");
    assertThat(row.get("source_url")).isEqualTo("https://www.notion.so/momens/scrum");
    assertThat(row.get("title")).isEqualTo("스크럼 노트");
    assertThat(row.get("visibility")).isEqualTo("WORKSPACE");
    assertThat(row.get("metadata")).hasToString("{}");
    assertThat(row.get("content_hash")).isNull();
    assertThat(row.get("created_at")).isNotNull();
    assertThat(row.get("updated_at")).isNotNull();
  }

  @Test
  @DisplayName("알 수 없는 종류는 LINK로 바꾸고 빈 제목은 저장하지 않는다")
  void fallsBackToLinkForUnknownSourceTypeAndStoresBlankTitleAsNull() {
    UUID sourceRefId =
        sourceRefWriter.createManualLink(
            new SourceRefWriter.NewManualLink(
                insertWorkspace(), "dropbox", "https://example.com/doc", "   "));

    Map<String, Object> row = row(sourceRefId);
    assertThat(row.get("source_type")).isEqualTo("LINK");
    assertThat(row.get("title")).isNull();
  }

  @Test
  @DisplayName("같은 주소를 두 번 붙이면 행을 두 개 만든다")
  void createsSeparateRowsForTheSameUrl() {
    UUID workspaceId = insertWorkspace();

    UUID first =
        sourceRefWriter.createManualLink(
            new SourceRefWriter.NewManualLink(
                workspaceId, "GITHUB", "https://github.com/momens/pull/1", null));
    UUID second =
        sourceRefWriter.createManualLink(
            new SourceRefWriter.NewManualLink(
                workspaceId, "GITHUB", "https://github.com/momens/pull/1", null));

    assertThat(second).isNotEqualTo(first);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM source_refs WHERE workspace_id = ?", Long.class, workspaceId))
        .isEqualTo(2L);
  }

  private UUID insertWorkspace() {
    return WorkspaceSeedSql.insertWorkspace(entityManager, "ws-" + UUID.randomUUID());
  }

  private Map<String, Object> row(UUID sourceRefId) {
    return jdbcTemplate.queryForMap("SELECT * FROM source_refs WHERE id = ?", sourceRefId);
  }
}
