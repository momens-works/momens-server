package works.momens.server.signal.dev;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import works.momens.server.common.api.BusinessException;
import works.momens.server.common.config.DevOnly;
import works.momens.server.outbox.OutboxAppender;
import works.momens.server.project.core.ProjectErrorCode;
import works.momens.server.project.core.ProjectReader;
import works.momens.server.signal.dev.dto.request.CreateDevSignalRequest;
import works.momens.server.source.DevSourceRefWriter;

/**
 * dev 데모용 Signal 원자 쓰기 트랜잭션(docs/design/signal-push-demo-design.md 5.5절).
 *
 * <p>evidence별 {@code source_refs}, {@code signals}, evidence별 {@code signal_evidence}, {@code
 * signal.created} outbox event를 한 DB 트랜잭션에서 저장한다. 하나라도 실패하면 모두 rollback한다. HTTP 성공은 위 데이터가
 * commit됐다는 뜻이며 FCM 발송 성공까지 기다리지 않는다. {@code @Immutable} 읽기 엔티티를 재사용하지 않는 전용 insert 경로다.
 */
@Slf4j
@DevOnly
@Service
@RequiredArgsConstructor
class DevSignalWriter {

  private final JdbcClient jdbcClient;
  private final ProjectReader projectReader;
  private final DevSourceRefWriter devSourceRefWriter;
  private final OutboxAppender outboxAppender;

  @Transactional
  public UUID create(UUID projectId, CreateDevSignalRequest request) {
    UUID workspaceId =
        projectReader
            .workspaceIdOf(projectId)
            .orElseThrow(() -> new BusinessException(ProjectErrorCode.PROJECT_NOT_FOUND));
    UUID signalId = UUID.randomUUID();
    insertSignal(signalId, workspaceId, projectId, request);
    List<CreateDevSignalRequest.Evidence> evidence = request.evidenceOrEmpty();
    for (int sortOrder = 0; sortOrder < evidence.size(); sortOrder++) {
      insertEvidence(signalId, workspaceId, evidence.get(sortOrder), sortOrder);
    }
    outboxAppender.append(workspaceId, "signal", signalId.toString(), "signal.created", Map.of());
    log.info(
        "dev signal created signalId={} projectId={} workspaceId={} evidence={}",
        signalId,
        projectId,
        workspaceId,
        evidence.size());
    return signalId;
  }

  private void insertSignal(
      UUID signalId, UUID workspaceId, UUID projectId, CreateDevSignalRequest request) {
    jdbcClient
        .sql(
            "INSERT INTO signals "
                + "(id, workspace_id, project_id, type, title, description, impact, "
                + "minsu_suggestion, occurred_at) "
                + "VALUES (:id, :workspaceId, :projectId, :type, :title, :description, :impact, "
                + ":minsuSuggestion, :occurredAt)")
        .param("id", signalId)
        .param("workspaceId", workspaceId)
        .param("projectId", projectId)
        .param("type", request.type().value())
        .param("title", request.title())
        .param("description", request.description())
        .param("impact", request.impact())
        .param("minsuSuggestion", request.minsuSuggestion())
        .param("occurredAt", timestampOf(request.occurredAt()))
        .update();
  }

  private void insertEvidence(
      UUID signalId, UUID workspaceId, CreateDevSignalRequest.Evidence evidence, int sortOrder) {
    UUID sourceRefId =
        devSourceRefWriter.insert(
            new DevSourceRefWriter.NewSourceRef(
                workspaceId,
                evidence.sourceType(),
                evidence.sourceTitle(),
                evidence.sourceSnippet(),
                evidence.sourceText(),
                evidence.sourceUrl(),
                evidence.occurredAt()));
    CreateDevSignalRequest.Evidence.Details details = evidence.details();
    jdbcClient
        .sql(
            "INSERT INTO signal_evidence "
                + "(workspace_id, signal_id, source_ref_id, sort_order, target, \"change\", impact) "
                + "VALUES (:workspaceId, :signalId, :sourceRefId, :sortOrder, :target, :change, "
                + ":impact)")
        .param("workspaceId", workspaceId)
        .param("signalId", signalId)
        .param("sourceRefId", sourceRefId)
        .param("sortOrder", sortOrder)
        .param("target", details == null ? null : details.target())
        .param("change", details == null ? null : details.change())
        .param("impact", details == null ? null : details.impact())
        .update();
  }

  // timestamptz 컬럼에 오프셋 없는 java.sql.Timestamp를 바인딩하면 드라이버가 JVM 기본 타임존으로 해석해
  // 값이 밀릴 수 있다. UTC OffsetDateTime으로 바인딩해 저장 순간(Instant)을 그대로 보존한다.
  private static OffsetDateTime timestampOf(Instant instant) {
    return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
  }
}
