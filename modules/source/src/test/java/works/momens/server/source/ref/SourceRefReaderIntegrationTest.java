package works.momens.server.source.ref;

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
import works.momens.server.source.SourceRefReader;
import works.momens.server.source.SourceRefView;
import works.momens.server.workspace.WorkspaceSeedSql;

/**
 * source_ref 조회 public API 검증.
 *
 * <p>실제 PostgreSQL(Testcontainers)에서 id 조회, 소프트 삭제 제외, 근거 카드 필드 매핑을 확인합니다. source_refs는 읽기 전용 외부
 * 테이블이므로 fixture는 네이티브 SQL로 삽입합니다(엔티티로 쓰지 않습니다).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(SourceRefReaderImpl.class)
@DisplayName("SourceRefReader 통합 테스트")
class SourceRefReaderIntegrationTest extends AbstractPostgresIntegrationTest {

  @Autowired private SourceRefReader sourceRefReader;
  @Autowired private TestEntityManager entityManager;

  @Test
  @DisplayName("id 목록 조회는 살아있는 source_ref만 반환하고 삭제·미존재 항목은 제외한다")
  void findByIdsReturnsLiveRefsAndSkipsDeletedAndUnknown() {
    UUID workspaceId = insertWorkspace();
    UUID live = insertSourceRef(workspaceId, "figma", "권한 요청 화면 v2", "설명 문구 변경", null);
    UUID deleted = insertSourceRef(workspaceId, "slack", "스레드", "삭제됨", Instant.now());

    List<SourceRefView> views =
        sourceRefReader.findByIds(workspaceId, List.of(live, deleted, UUID.randomUUID()));

    assertThat(views).extracting(SourceRefView::id).containsExactly(live);
  }

  @Test
  @DisplayName("id가 맞아도 다른 워크스페이스의 source_ref는 반환하지 않는다")
  void findByIdsExcludesOtherWorkspace() {
    UUID workspaceId = insertWorkspace();
    UUID otherWorkspaceId = insertWorkspace();
    UUID mine = insertSourceRef(workspaceId, "figma", "내 화면", "설명", null);
    UUID foreign = insertSourceRef(otherWorkspaceId, "slack", "남의 스레드", "설명", null);

    List<SourceRefView> views = sourceRefReader.findByIds(workspaceId, List.of(mine, foreign));

    assertThat(views).extracting(SourceRefView::id).containsExactly(mine);
  }

  @Test
  @DisplayName("빈 id 목록이면 DB 조회 없이 빈 결과를 반환한다")
  void findByIdsIsEmptyForEmptyIds() {
    assertThat(sourceRefReader.findByIds(UUID.randomUUID(), List.of())).isEmpty();
  }

  @Test
  @DisplayName("source_refs 컬럼을 Signal evidence 카드에 필요한 SourceRefView 필드로 매핑한다")
  void findByIdsMapsEvidenceCardFields() {
    UUID workspaceId = insertWorkspace();
    Instant occurredAt = Instant.parse("2026-06-28T00:48:00Z");
    UUID id =
        insertFullSourceRef(
            workspaceId,
            "figma",
            "권한 요청 화면 v2",
            "설명 문구 변경으로 이탈 가능성이 있습니다.",
            "권한 요청 화면 전체 본문",
            "https://figma.example/permission",
            occurredAt);

    SourceRefView view = sourceRefReader.findByIds(workspaceId, List.of(id)).getFirst();

    assertThat(view)
        .isEqualTo(
            new SourceRefView(
                id,
                "figma",
                "권한 요청 화면 v2",
                "설명 문구 변경으로 이탈 가능성이 있습니다.",
                "권한 요청 화면 전체 본문",
                "https://figma.example/permission",
                occurredAt));
  }

  private UUID insertWorkspace() {
    return WorkspaceSeedSql.insertWorkspace(entityManager, "ws-" + UUID.randomUUID());
  }

  private UUID insertSourceRef(
      UUID workspaceId, String sourceType, String title, String snippet, Instant deletedAt) {
    UUID id = UUID.randomUUID();
    entityManager
        .getEntityManager()
        .createNativeQuery(
            "INSERT INTO source_refs (id, workspace_id, source_type, source_object_type,"
                + " source_object_id, title, snippet, deleted_at)"
                + " VALUES (?1, ?2, ?3, 'FILE_COMMENT', 'obj-1', ?4, ?5, ?6)")
        .setParameter(1, id)
        .setParameter(2, workspaceId)
        .setParameter(3, sourceType)
        .setParameter(4, title)
        .setParameter(5, snippet)
        .setParameter(6, deletedAt)
        .executeUpdate();
    entityManager.clear();
    return id;
  }

  private UUID insertFullSourceRef(
      UUID workspaceId,
      String sourceType,
      String title,
      String snippet,
      String text,
      String sourceUrl,
      Instant sourceCreatedAt) {
    UUID id = UUID.randomUUID();
    entityManager
        .getEntityManager()
        .createNativeQuery(
            "INSERT INTO source_refs (id, workspace_id, source_type, source_object_type,"
                + " source_object_id, title, snippet, text, source_url, source_created_at)"
                + " VALUES (?1, ?2, ?3, 'FILE_COMMENT', 'obj-1', ?4, ?5, ?6, ?7, ?8)")
        .setParameter(1, id)
        .setParameter(2, workspaceId)
        .setParameter(3, sourceType)
        .setParameter(4, title)
        .setParameter(5, snippet)
        .setParameter(6, text)
        .setParameter(7, sourceUrl)
        .setParameter(8, sourceCreatedAt)
        .executeUpdate();
    entityManager.clear();
    return id;
  }
}
