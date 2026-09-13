package works.momens.server.signal.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import works.momens.server.common.api.BusinessException;
import works.momens.server.common.api.CommonErrorCode;
import works.momens.server.common.test.AbstractPostgresIntegrationTest;
import works.momens.server.signal.SignalDetail;
import works.momens.server.signal.SignalDetailService;
import works.momens.server.signal.SignalErrorCode;
import works.momens.server.source.SourceRefReader;
import works.momens.server.source.SourceRefView;
import works.momens.server.workspace.membership.WorkspaceMembershipReader;
import works.momens.server.workspace.membership.WorkspaceRole;

/**
 * Signal 상세 조립 검증. 접근 검사와 source hydrate(각각 mock), 근거의 의미 값(대상·변화·영향)과 정렬·원본 누락 제외, 처리·삭제된 Signal의
 * SIGNAL_NOT_FOUND를 실제 PostgreSQL(Testcontainers)로 확인합니다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class SignalDetailServiceImplTest extends AbstractPostgresIntegrationTest {

  private static final UUID WORKSPACE_ID = UUID.randomUUID();
  private static final UUID PROJECT_ID = UUID.randomUUID();
  private static final UUID CALLER_ID = UUID.randomUUID();

  @Autowired private SignalRepository signalRepository;
  @Autowired private SignalEvidenceRepository signalEvidenceRepository;
  @Autowired private TestEntityManager entityManager;

  private final WorkspaceMembershipReader workspaceMembershipReader =
      mock(WorkspaceMembershipReader.class);
  private final SourceRefReader sourceRefReader = mock(SourceRefReader.class);
  private SignalDetailService signalDetailService;

  @BeforeEach
  void setUp() {
    signalDetailService =
        new SignalDetailServiceImpl(
            signalRepository, signalEvidenceRepository, workspaceMembershipReader, sourceRefReader);
  }

  @Test
  @DisplayName("Signal이 없으면 SIGNAL_NOT_FOUND를 던진다")
  void throwsSignalNotFoundWhenMissing() {
    assertThatThrownBy(() -> signalDetailService.getDetail(UUID.randomUUID(), CALLER_ID))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(SignalErrorCode.SIGNAL_NOT_FOUND);
  }

  @Test
  @DisplayName("이미 처리된 Signal은 SIGNAL_NOT_FOUND를 던진다")
  void throwsSignalNotFoundWhenProcessed() {
    UUID signalId = insertSignal();
    insertAction(signalId);

    assertThatThrownBy(() -> signalDetailService.getDetail(signalId, CALLER_ID))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(SignalErrorCode.SIGNAL_NOT_FOUND);
  }

  @Test
  @DisplayName("workspace 멤버가 아니면 AUTH_FORBIDDEN을 던진다")
  void throwsForbiddenWhenNotMember() {
    UUID signalId = insertSignal();
    when(workspaceMembershipReader.roleOf(WORKSPACE_ID, CALLER_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> signalDetailService.getDetail(signalId, CALLER_ID))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(CommonErrorCode.AUTH_FORBIDDEN);
  }

  @Test
  @DisplayName("근거를 sort_order 순으로 조립해 의미 값은 evidence에서, source는 source_ref에서 채우고 원본 없는 근거는 제외한다")
  void assemblesEvidenceInSortOrderFromEvidenceAndSourceRefAndSkipsMissing() {
    UUID signalId = insertSignal();
    UUID ref0 = UUID.randomUUID();
    UUID ref1 = UUID.randomUUID();
    UUID missing = UUID.randomUUID();
    insertEvidence(signalId, ref1, 1, "대상1", "변화1", "영향1");
    insertEvidence(signalId, ref0, 0, "대상0", "변화0", "영향0");
    insertEvidence(signalId, missing, 2, "대상x", "변화x", "영향x");

    when(workspaceMembershipReader.roleOf(WORKSPACE_ID, CALLER_ID))
        .thenReturn(Optional.of(WorkspaceRole.MEMBER));
    // missing은 source 원본이 반환되지 않아 제외된다.
    when(sourceRefReader.findByIds(any(), any()))
        .thenReturn(
            List.of(
                new SourceRefView(ref0, "slack", "스레드", null, "본문0", "https://s/0", null),
                new SourceRefView(
                    ref1,
                    "figma",
                    "화면",
                    "요약1",
                    "본문1",
                    "https://f/1",
                    Instant.parse("2026-07-06T00:00:00Z"))));

    SignalDetail detail = signalDetailService.getDetail(signalId, CALLER_ID);

    assertThat(detail.evidence())
        .extracting(SignalDetail.Evidence::sourceRefId)
        .containsExactly(ref0, ref1);
    SignalDetail.Evidence first = detail.evidence().get(0);
    assertThat(first.source()).isEqualTo("slack");
    assertThat(first.occurredAt()).isNull();
    assertThat(first.target()).isEqualTo("대상0");
    assertThat(first.change()).isEqualTo("변화0");
    assertThat(first.impact()).isEqualTo("영향0");
    assertThat(first.sourceUrl()).isEqualTo("https://s/0");
    SignalDetail.Evidence second = detail.evidence().get(1);
    assertThat(second.source()).isEqualTo("figma");
    assertThat(second.occurredAt()).isEqualTo(Instant.parse("2026-07-06T00:00:00Z"));
    assertThat(second.target()).isEqualTo("대상1");
  }

  @Test
  @DisplayName("sort_order가 같으면 source_ref_id 오름차순으로 순서를 고정한다")
  void tieBreaksBySourceRefIdWhenSortOrderEqual() {
    UUID signalId = insertSignal();
    UUID low = UUID.fromString("00000000-0000-0000-0000-000000000001");
    UUID high = UUID.fromString("00000000-0000-0000-0000-000000000002");
    insertEvidence(signalId, high, 0, "대상h", "변화h", "영향h");
    insertEvidence(signalId, low, 0, "대상l", "변화l", "영향l");

    when(workspaceMembershipReader.roleOf(WORKSPACE_ID, CALLER_ID))
        .thenReturn(Optional.of(WorkspaceRole.MEMBER));
    when(sourceRefReader.findByIds(any(), any()))
        .thenReturn(
            List.of(
                new SourceRefView(high, "slack", "높음", "요약", "본문", "https://s/h", null),
                new SourceRefView(low, "figma", "낮음", "요약", "본문", "https://s/l", null)));

    SignalDetail detail = signalDetailService.getDetail(signalId, CALLER_ID);

    assertThat(detail.evidence())
        .extracting(SignalDetail.Evidence::sourceRefId)
        .containsExactly(low, high);
  }

  private UUID insertSignal() {
    UUID id = UUID.randomUUID();
    entityManager
        .getEntityManager()
        .createNativeQuery(
            "INSERT INTO signals (id, workspace_id, project_id, type, title, description)"
                + " VALUES (?1, ?2, ?3, ?4, ?5, ?6)")
        .setParameter(1, id)
        .setParameter(2, WORKSPACE_ID)
        .setParameter(3, PROJECT_ID)
        .setParameter(4, "risk")
        .setParameter(5, "제목")
        .setParameter(6, "본문")
        .executeUpdate();
    entityManager.clear();
    return id;
  }

  private void insertAction(UUID signalId) {
    entityManager
        .getEntityManager()
        .createNativeQuery(
            "INSERT INTO signal_actions (id, workspace_id, signal_id, action_type,"
                + " processed_by_user_id) VALUES (?1, ?2, ?3, ?4, ?5)")
        .setParameter(1, UUID.randomUUID())
        .setParameter(2, WORKSPACE_ID)
        .setParameter(3, signalId)
        .setParameter(4, "dismiss")
        .setParameter(5, UUID.randomUUID())
        .executeUpdate();
    entityManager.clear();
  }

  private void insertEvidence(
      UUID signalId, UUID sourceRefId, int sortOrder, String target, String change, String impact) {
    entityManager
        .getEntityManager()
        .createNativeQuery(
            "INSERT INTO signal_evidence (workspace_id, signal_id, source_ref_id, sort_order,"
                + " target, \"change\", impact) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)")
        .setParameter(1, WORKSPACE_ID)
        .setParameter(2, signalId)
        .setParameter(3, sourceRefId)
        .setParameter(4, sortOrder)
        .setParameter(5, target)
        .setParameter(6, change)
        .setParameter(7, impact)
        .executeUpdate();
    entityManager.clear();
  }
}
