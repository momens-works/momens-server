package works.momens.server.signal.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import works.momens.server.common.test.AbstractPostgresIntegrationTest;
import works.momens.server.signal.SignalReader;

/**
 * action 모듈이 쓰는 read seam 검증(MOM-0804).
 *
 * <p>snapshot의 task draft 입력 컬럼 매핑과 {@code findDraftEvidence}의 결정적 순서·빈 목록 계약을 실제 PostgreSQL로
 * 확인합니다. signals·signal_evidence는 worker가 쓰는 테이블이라 fixture는 네이티브 SQL로 삽입합니다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(SignalReaderImpl.class)
class SignalReaderImplIntegrationTest extends AbstractPostgresIntegrationTest {

  @Autowired private SignalReader signalReader;
  @Autowired private JdbcClient jdbcClient;

  @Test
  @DisplayName("snapshot에 task draft 입력인 type·description·impact를 포함한다")
  void findLiveMapsDraftInputColumns() {
    UUID workspaceId = UUID.randomUUID();
    UUID projectId = UUID.randomUUID();
    UUID signalId = insertSignal(workspaceId, projectId);

    SignalReader.Snapshot snapshot = signalReader.findLive(signalId).orElseThrow();

    assertThat(snapshot)
        .isEqualTo(
            new SignalReader.Snapshot(
                signalId,
                workspaceId,
                projectId,
                "decision",
                "결제 정책 결정 3일째 보류",
                "결제 수단 범위 합의가 지연되고 있습니다.",
                "출시 일정에 영향을 줄 수 있습니다."));
  }

  @Test
  @DisplayName("evidence를 sort_order, source_ref_id 순으로 반환한다")
  void findDraftEvidenceReturnsDeterministicOrder() {
    UUID workspaceId = UUID.randomUUID();
    UUID signalId = insertSignal(workspaceId, UUID.randomUUID());
    // sort_order가 같은 두 행의 순서는 source_ref_id로 결정되어야 하므로 값이 큰 쪽을 먼저 삽입합니다.
    UUID higherRef = UUID.fromString("ffffffff-ffff-4fff-8fff-ffffffffffff");
    UUID lowerRef = UUID.fromString("00000000-0000-4000-8000-000000000001");
    insertEvidence(workspaceId, signalId, higherRef, 1, "환불 정책", "합의 없음", "CS 부담");
    insertEvidence(workspaceId, signalId, lowerRef, 1, "결제 정책", "논의 중단", "출시 지연");
    insertEvidence(workspaceId, signalId, UUID.randomUUID(), 0, "결제 수단", "범위 미확정", "개발 대기");

    assertThat(signalReader.findDraftEvidence(signalId))
        .containsExactly(
            new SignalReader.DraftEvidence("결제 수단", "범위 미확정", "개발 대기"),
            new SignalReader.DraftEvidence("결제 정책", "논의 중단", "출시 지연"),
            new SignalReader.DraftEvidence("환불 정책", "합의 없음", "CS 부담"));
  }

  @Test
  @DisplayName("근거가 없으면 빈 목록을 반환한다")
  void findDraftEvidenceReturnsEmptyListWhenNoEvidence() {
    UUID signalId = insertSignal(UUID.randomUUID(), UUID.randomUUID());

    assertThat(signalReader.findDraftEvidence(signalId)).isEmpty();
  }

  @Test
  @DisplayName("task draft에는 정렬된 의미 있는 evidence를 최대 10건 반환한다")
  void findDraftEvidenceLimitsMeaningfulEvidence() {
    UUID workspaceId = UUID.randomUUID();
    UUID signalId = insertSignal(workspaceId, UUID.randomUUID());
    insertEvidence(workspaceId, signalId, UUID.randomUUID(), 0, " \t", "\n", "\r");
    IntStream.range(0, 12)
        .forEach(index ->
            insertEvidence(
                workspaceId,
                signalId,
                UUID.randomUUID(),
                index + 1,
                "대상" + index,
                "변화" + index,
                "영향" + index));

    assertThat(signalReader.findDraftEvidence(signalId))
        .extracting(SignalReader.DraftEvidence::target)
        .containsExactlyElementsOf(IntStream.range(0, 10).mapToObj(index -> "대상" + index).toList());
  }

  private UUID insertSignal(UUID workspaceId, UUID projectId) {
    UUID signalId = UUID.randomUUID();
    jdbcClient
        .sql(
            "INSERT INTO signals (id, workspace_id, project_id, type, title, description, impact,"
                + " occurred_at) VALUES (?, ?, ?, ?, ?, ?, ?, now())")
        .params(
            List.of(
                signalId,
                workspaceId,
                projectId,
                "decision",
                "결제 정책 결정 3일째 보류",
                "결제 수단 범위 합의가 지연되고 있습니다.",
                "출시 일정에 영향을 줄 수 있습니다."))
        .update();
    return signalId;
  }

  private void insertEvidence(
      UUID workspaceId,
      UUID signalId,
      UUID sourceRefId,
      int sortOrder,
      String target,
      String change,
      String impact) {
    jdbcClient
        .sql(
            "INSERT INTO signal_evidence (workspace_id, signal_id, source_ref_id, sort_order,"
                + " target, \"change\", impact) VALUES (?, ?, ?, ?, ?, ?, ?)")
        .params(List.of(workspaceId, signalId, sourceRefId, sortOrder, target, change, impact))
        .update();
  }
}
