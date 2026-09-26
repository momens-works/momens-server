package works.momens.server.source.ref;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import works.momens.server.common.api.BusinessException;
import works.momens.server.source.SourceErrorCode;
import works.momens.server.source.SourceRefDetail;
import works.momens.server.source.SourceRefVerifier;

/**
 * source-ref 검증 표시를 조건부 갱신으로 처리합니다.
 *
 * <p>대상을 조회한 뒤 갱신하지 않고 하나의 갱신 쿼리로 처리합니다. 대상이 없거나 이미 소프트 삭제된 경우는 갱신된 행 수로 판정하므로, 조회와 갱신 사이에 대상이 삭제되는
 * 경우에도 동일한 결과를 반환합니다.
 *
 * <p>읽기 전용으로 매핑된 엔티티는 사용하지 않고 갱신합니다. 해당 엔티티는 변경할 수 없도록 선언되어 있으며, 같은 이유로 별도의 쓰기 경로가 이미 마련되어 있습니다.
 */
@Component
@RequiredArgsConstructor
class SourceRefVerifierImpl implements SourceRefVerifier {

  private static final String COLUMNS =
      "id, workspace_id, source_type, source_connection_id, source_object_type, source_object_id,"
          + " source_url, title, text, snippet, author_name, author_email, source_created_at,"
          + " source_updated_at, visibility, permission_key, verified_by_user_id, verified_at,"
          + " metadata, created_at, updated_at";

  private final JdbcClient jdbcClient;

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Override
  @Transactional
  public SourceRefDetail verify(UUID sourceRefId, UUID userId) {
    int updated =
        jdbcClient
            .sql(
                "UPDATE source_refs SET verified_by_user_id = :userId, verified_at = :now,"
                    + " updated_at = :now WHERE id = :id AND deleted_at IS NULL")
            .param("userId", userId)
            .param("now", Timestamp.from(Instant.now()))
            .param("id", sourceRefId)
            .update();
    if (updated == 0) {
      throw new BusinessException(
          SourceErrorCode.SOURCE_REF_NOT_FOUND, Map.of("source_ref_id", sourceRefId.toString()));
    }
    return jdbcClient
        .sql("SELECT " + COLUMNS + " FROM source_refs WHERE id = :id AND deleted_at IS NULL")
        .param("id", sourceRefId)
        .query(this::toDetail)
        .single();
  }

  private SourceRefDetail toDetail(ResultSet rs, int rowNum) throws SQLException {
    return new SourceRefDetail(
        rs.getObject("id", UUID.class),
        rs.getObject("workspace_id", UUID.class),
        rs.getString("source_type"),
        rs.getObject("source_connection_id", UUID.class),
        rs.getString("source_object_type"),
        rs.getString("source_object_id"),
        rs.getString("source_url"),
        rs.getString("title"),
        rs.getString("text"),
        rs.getString("snippet"),
        rs.getString("author_name"),
        rs.getString("author_email"),
        instant(rs, "source_created_at"),
        instant(rs, "source_updated_at"),
        rs.getString("visibility"),
        rs.getString("permission_key"),
        rs.getObject("verified_by_user_id", UUID.class),
        instant(rs, "verified_at"),
        metadata(rs.getString("metadata")),
        instant(rs, "created_at"),
        instant(rs, "updated_at"));
  }

  private Map<String, Object> metadata(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return objectMapper.readValue(raw, new TypeReference<Map<String, Object>>() {});
    } catch (JsonProcessingException e) {
      return null;
    }
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp value = rs.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }
}
