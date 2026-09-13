package works.momens.server.source.ref;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import works.momens.server.common.config.DevOnly;
import works.momens.server.source.DevSourceRefWriter;

/**
 * dev 전용 source_refs insert. {@code @Immutable} 읽기 엔티티({@code SourceRef})를 재사용하지 않는 전용 insert
 * 경로다(docs/design/signal-push-demo-design.md 11.2절).
 *
 * <p>{@code source_object_type}과 {@code source_object_id}는 운영 환경에서 {@code NOT NULL}이고 기본값이 없으므로 서버가
 * 직접 저장한다(설계 문서 5.4절). 데모에서는 연결된 외부 객체의 종류와 식별자를 알 수 없으므로 {@code source_object_type}에는 프로토 enum의
 * {@code SOURCE_OBJECT_TYPE_UNKNOWN}에 해당하는 값을 저장하고, {@code source_object_id}에는 생성한 source ref ID를
 * 저장한다. 레거시 projection이 생성한 객체의 UUID를 같은 컬럼에 저장하는 방식과 동일하다.
 */
@DevOnly
@Component
@RequiredArgsConstructor
class DevSourceRefWriterImpl implements DevSourceRefWriter {

  private static final String DEMO_EVIDENCE_OBJECT_TYPE = "UNKNOWN";

  private final JdbcClient jdbcClient;

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public UUID insert(NewSourceRef sourceRef) {
    UUID id = UUID.randomUUID();
    jdbcClient
        .sql(
            "INSERT INTO source_refs "
                + "(id, workspace_id, source_type, source_object_type, source_object_id, title, snippet, "
                + "text, source_url, source_created_at) "
                + "VALUES (:id, :workspaceId, :sourceType, :sourceObjectType, :sourceObjectId, :title, "
                + ":snippet, :text, :sourceUrl, :sourceCreatedAt)")
        .param("id", id)
        .param("workspaceId", sourceRef.workspaceId())
        .param("sourceObjectType", DEMO_EVIDENCE_OBJECT_TYPE)
        .param("sourceObjectId", id.toString())
        .param("sourceType", sourceRef.sourceType())
        .param("title", sourceRef.title())
        .param("snippet", sourceRef.snippet())
        .param("text", sourceRef.text())
        .param("sourceUrl", sourceRef.sourceUrl())
        .param("sourceCreatedAt", timestampOf(sourceRef))
        .update();
    return id;
  }

  // timestamptz 컬럼에 오프셋 없는 java.sql.Timestamp를 바인딩하면 드라이버가 JVM 기본 타임존으로 해석해
  // 값이 밀릴 수 있다. UTC OffsetDateTime으로 바인딩해 원천 발생 순간(Instant)을 그대로 보존한다.
  private static OffsetDateTime timestampOf(NewSourceRef sourceRef) {
    return sourceRef.sourceCreatedAt() == null
        ? null
        : sourceRef.sourceCreatedAt().atOffset(ZoneOffset.UTC);
  }
}
