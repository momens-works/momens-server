package works.momens.server.signal.dev;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import works.momens.server.common.api.BusinessException;
import works.momens.server.common.test.AbstractPostgresIntegrationTest;
import works.momens.server.outbox.OutboxAppender;
import works.momens.server.project.core.ProjectReader;
import works.momens.server.signal.SignalType;
import works.momens.server.signal.dev.dto.request.CreateDevSignalRequest;
import works.momens.server.source.DevSourceRefWriter;

/**
 * dev Signal 생성의 signals·signal_evidence insert, source_refs·outbox 위임, 없는 project 거부를 실제
 * PostgreSQL로 검증합니다. project·source·outbox public API는 다른 모듈 소유라 mock으로 둡니다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(DevSignalWriter.class)
class DevSignalWriterIntegrationTest extends AbstractPostgresIntegrationTest {

  private static final UUID PROJECT_ID = UUID.randomUUID();
  private static final UUID WORKSPACE_ID = UUID.randomUUID();

  @Autowired private DevSignalWriter writer;
  @Autowired private JdbcClient jdbcClient;

  @MockitoBean private ProjectReader projectReader;
  @MockitoBean private DevSourceRefWriter devSourceRefWriter;
  @MockitoBean private OutboxAppender outboxAppender;

  @Test
  @DisplayName("signals와 evidence별 signal_evidence를 저장하고 source_refs·outbox 발행을 위임한다")
  void createsSignalWithEvidence() {
    when(projectReader.workspaceIdOf(PROJECT_ID)).thenReturn(Optional.of(WORKSPACE_ID));
    UUID firstSourceRef = UUID.randomUUID();
    UUID secondSourceRef = UUID.randomUUID();
    when(devSourceRefWriter.insert(any())).thenReturn(firstSourceRef).thenReturn(secondSourceRef);
    Instant occurredAt = Instant.parse("2026-07-16T01:30:00Z");

    UUID signalId =
        writer.create(
            PROJECT_ID,
            new CreateDevSignalRequest(
                SignalType.RISK,
                "결제 정책 결정 3일째 보류",
                "결제 정책 결정이 3일 동안 보류된 상태입니다.",
                "Q2 Activation 일정 지연 가능성",
                "결제 정책 의사결정 미팅 제안",
                occurredAt,
                List.of(
                    new CreateDevSignalRequest.Evidence(
                        "slack",
                        "결제 정책 논의",
                        "정책 결정이 아직 보류 상태입니다.",
                        "원문 전체 내용",
                        "https://example.com/source/1",
                        occurredAt,
                        new CreateDevSignalRequest.Evidence.Details(
                            "결제 정책", "결정 3일째 보류", "일정 지연 가능")),
                    new CreateDevSignalRequest.Evidence(
                        "github", null, null, null, null, null, null))));

    Map<String, Object> signal =
        jdbcClient
            .sql("SELECT * FROM signals WHERE id = :id")
            .param("id", signalId)
            .query()
            .singleRow();
    assertThat(signal)
        .containsEntry("workspace_id", WORKSPACE_ID)
        .containsEntry("project_id", PROJECT_ID)
        .containsEntry("type", "risk")
        .containsEntry("title", "결제 정책 결정 3일째 보류")
        .containsEntry("impact", "Q2 Activation 일정 지연 가능성")
        .containsEntry("minsu_suggestion", "결제 정책 의사결정 미팅 제안");
    assertThat(signal.get("metadata")).isNull();
    // occurred_at이 타임존 이동 없이 저장 순간(Instant) 그대로 왕복하는지 확인한다.
    assertThat(((Timestamp) signal.get("occurred_at")).toInstant()).isEqualTo(occurredAt);

    List<Map<String, Object>> evidence =
        jdbcClient
            .sql("SELECT * FROM signal_evidence WHERE signal_id = :id ORDER BY sort_order")
            .param("id", signalId)
            .query()
            .listOfRows();
    assertThat(evidence).hasSize(2);
    assertThat(evidence.get(0))
        .containsEntry("source_ref_id", firstSourceRef)
        .containsEntry("sort_order", 0)
        .containsEntry("target", "결제 정책")
        .containsEntry("change", "결정 3일째 보류")
        .containsEntry("impact", "일정 지연 가능");
    assertThat(evidence.get(1))
        .containsEntry("source_ref_id", secondSourceRef)
        .containsEntry("sort_order", 1)
        .containsEntry("target", null);

    ArgumentCaptor<DevSourceRefWriter.NewSourceRef> sourceRef =
        ArgumentCaptor.forClass(DevSourceRefWriter.NewSourceRef.class);
    verify(devSourceRefWriter, times(2)).insert(sourceRef.capture());
    assertThat(sourceRef.getAllValues().getFirst().workspaceId()).isEqualTo(WORKSPACE_ID);
    assertThat(sourceRef.getAllValues().getFirst().sourceType()).isEqualTo("slack");
    assertThat(sourceRef.getAllValues().getFirst().text()).isEqualTo("원문 전체 내용");

    verify(outboxAppender)
        .append(WORKSPACE_ID, "signal", signalId.toString(), "signal.created", Map.of());
  }

  @Test
  @DisplayName("evidence 없는 Signal은 signals와 outbox 발행만 남긴다")
  void createsSignalWithoutEvidence() {
    when(projectReader.workspaceIdOf(PROJECT_ID)).thenReturn(Optional.of(WORKSPACE_ID));

    UUID signalId =
        writer.create(
            PROJECT_ID,
            new CreateDevSignalRequest(SignalType.DECISION, "제목", "설명", null, null, null, null));

    assertThat(
            jdbcClient
                .sql("SELECT count(*) FROM signals WHERE id = :id")
                .param("id", signalId)
                .query(Long.class)
                .single())
        .isEqualTo(1);
    assertThat(
            jdbcClient
                .sql("SELECT count(*) FROM signal_evidence WHERE signal_id = :id")
                .param("id", signalId)
                .query(Long.class)
                .single())
        .isZero();
    verifyNoInteractions(devSourceRefWriter);
    verify(outboxAppender)
        .append(WORKSPACE_ID, "signal", signalId.toString(), "signal.created", Map.of());
  }

  @Test
  @DisplayName("없는 project는 PROJECT_NOT_FOUND로 거부한다")
  void rejectsUnknownProject() {
    when(projectReader.workspaceIdOf(PROJECT_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                writer.create(
                    PROJECT_ID,
                    new CreateDevSignalRequest(
                        SignalType.RISK, "제목", "설명", null, null, null, null)))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            e ->
                assertThat(((BusinessException) e).getErrorCode().code())
                    .isEqualTo("PROJECT_NOT_FOUND"));
    verifyNoInteractions(outboxAppender);
  }
}
