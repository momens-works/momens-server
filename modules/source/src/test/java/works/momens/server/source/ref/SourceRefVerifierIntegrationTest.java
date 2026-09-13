package works.momens.server.source.ref;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import works.momens.server.common.api.BusinessException;
import works.momens.server.common.test.AbstractPostgresIntegrationTest;
import works.momens.server.source.SourceErrorCode;
import works.momens.server.source.SourceRefDetail;
import works.momens.server.source.SourceRefReader;
import works.momens.server.source.SourceRefVerifier;
import works.momens.server.workspace.WorkspaceSeedSql;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({SourceRefVerifierImpl.class, SourceRefReaderImpl.class})
/**
 * source-ref 검증 표시를 실제 PostgreSQL에서 검증합니다.
 *
 * <p>검증 시 갱신되는 컬럼과 반환 필드, 대상이 없거나 소프트 삭제된 경우의 처리 방식을 확인합니다.
 */
class SourceRefVerifierIntegrationTest extends AbstractPostgresIntegrationTest {

  @Autowired private SourceRefVerifier sourceRefVerifier;
  @Autowired private SourceRefReader sourceRefReader;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private TestEntityManager entityManager;

  @Test
  @DisplayName("source-ref를 검증 완료로 표시하고 레거시 응답의 모든 필드를 반환한다")
  void marksSourceRefVerifiedAndReturnsEveryFieldTheLegacyResponseCarries() {
    UUID workspaceId = insertWorkspace();
    UUID userId = insertUser();
    UUID sourceRefId = insertSourceRef(workspaceId, null);

    SourceRefDetail detail = sourceRefVerifier.verify(sourceRefId, userId);

    assertThat(detail.id()).isEqualTo(sourceRefId);
    assertThat(detail.workspaceId()).isEqualTo(workspaceId);
    assertThat(detail.verifiedByUserId()).isEqualTo(userId);
    assertThat(detail.verifiedAt()).isNotNull();
    assertThat(detail.title()).isEqualTo("권한 요청 화면 v2");
    assertThat(detail.text()).isEqualTo("수집한 원문 전체");
    assertThat(detail.snippet()).isEqualTo("설명 문구 변경");
    assertThat(detail.metadata()).containsEntry("thread_ts", "1712.34");
  }

  @Test
  @DisplayName("source-ref를 다시 검증하면 마지막으로 검증한 사용자로 덮어쓴다")
  void overwritesAnEarlierVerification() {
    UUID sourceRefId = insertSourceRef(insertWorkspace(), null);
    UUID firstUser = insertUser();
    UUID secondUser = insertUser();

    sourceRefVerifier.verify(sourceRefId, firstUser);
    SourceRefDetail second = sourceRefVerifier.verify(sourceRefId, secondUser);

    assertThat(second.verifiedByUserId()).isEqualTo(secondUser);
  }

  @Test
  @DisplayName("존재하지 않는 source-ref는 거부한다")
  void rejectsSourceRefThatDoesNotExist() {
    UUID missing = UUID.randomUUID();

    assertThatThrownBy(() -> sourceRefVerifier.verify(missing, UUID.randomUUID()))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorCode", SourceErrorCode.SOURCE_REF_NOT_FOUND);
  }

  @Test
  @DisplayName("소프트 삭제된 source-ref는 거부한다")
  void rejectsSourceRefThatIsSoftDeleted() {
    UUID sourceRefId = insertSourceRef(insertWorkspace(), Instant.now());

    assertThatThrownBy(() -> sourceRefVerifier.verify(sourceRefId, UUID.randomUUID()))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorCode", SourceErrorCode.SOURCE_REF_NOT_FOUND);
  }

  @Test
  @DisplayName("소프트 삭제되지 않은 source-ref의 워크스페이스만 조회한다")
  void findsWorkspaceOfLiveSourceRefOnly() {
    UUID workspaceId = insertWorkspace();
    UUID live = insertSourceRef(workspaceId, null);
    UUID deleted = insertSourceRef(workspaceId, Instant.now());

    assertThat(sourceRefReader.findWorkspaceId(live)).contains(workspaceId);
    assertThat(sourceRefReader.findWorkspaceId(deleted)).isEmpty();
    assertThat(sourceRefReader.findWorkspaceId(UUID.randomUUID())).isEmpty();
  }

  private UUID insertWorkspace() {
    return WorkspaceSeedSql.insertWorkspace(entityManager, "ws-" + UUID.randomUUID());
  }

  private UUID insertUser() {
    return WorkspaceSeedSql.insertUser(entityManager, UUID.randomUUID() + "@momens.works");
  }

  private UUID insertSourceRef(UUID workspaceId, Instant deletedAt) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO source_refs (id, workspace_id, source_type, source_object_type,"
            + " source_object_id, title, snippet, text, visibility, metadata, deleted_at)"
            + " VALUES (?, ?, 'figma', 'FILE_COMMENT', 'obj-1', ?, ?, ?, 'WORKSPACE',"
            + " CAST(? AS jsonb), ?)",
        id,
        workspaceId,
        "권한 요청 화면 v2",
        "설명 문구 변경",
        "수집한 원문 전체",
        "{\"thread_ts\": \"1712.34\"}",
        deletedAt == null ? null : java.sql.Timestamp.from(deletedAt));
    return id;
  }
}
