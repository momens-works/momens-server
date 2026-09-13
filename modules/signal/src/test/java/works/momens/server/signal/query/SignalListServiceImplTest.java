package works.momens.server.signal.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import works.momens.server.common.api.BusinessException;
import works.momens.server.common.api.CommonErrorCode;
import works.momens.server.common.test.AbstractPostgresIntegrationTest;
import works.momens.server.project.core.ProjectErrorCode;
import works.momens.server.project.core.ProjectReader;
import works.momens.server.signal.SignalListService;
import works.momens.server.signal.SignalSummary;
import works.momens.server.signal.SignalSummaryPage;
import works.momens.server.workspace.membership.WorkspaceMembershipReader;
import works.momens.server.workspace.membership.WorkspaceRole;

/**
 * Signal 목록 서비스 검증.
 *
 * <p>접근 검사(project와 workspace public API는 mock)와 미처리 조회, 당일 범위 조회를 실제 PostgreSQL Testcontainers로
 * 확인합니다. Signal 엔티티는 {@code @Immutable}이라 fixture는 네이티브 SQL로 삽입합니다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(SignalListServiceImpl.class)
class SignalListServiceImplTest extends AbstractPostgresIntegrationTest {

  @Autowired private SignalListService signalListService;
  @Autowired private TestEntityManager entityManager;
  @MockitoBean private ProjectReader projectReader;
  @MockitoBean private WorkspaceMembershipReader workspaceMembershipReader;

  private static final UUID PROJECT_ID = UUID.randomUUID();
  private static final UUID WORKSPACE_ID = UUID.randomUUID();
  private static final UUID CALLER_ID = UUID.randomUUID();
  // 페이지 동작을 검증하는 테스트는 시드한 Signal을 모두 담도록 넓은 범위를 쓴다.
  private static final Instant WIDE_FROM = Instant.parse("2026-01-01T00:00:00Z");
  private static final Instant WIDE_TO = Instant.parse("2027-01-01T00:00:00Z");

  @Test
  @DisplayName("project가 없으면 PROJECT_NOT_FOUND를 던진다")
  void throwsProjectNotFoundWhenProjectMissing() {
    when(projectReader.workspaceIdOf(PROJECT_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> signalListService.listUnprocessed(PROJECT_ID, CALLER_ID))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(ProjectErrorCode.PROJECT_NOT_FOUND);
  }

  @Test
  @DisplayName("workspace 멤버가 아니면 AUTH_FORBIDDEN을 던진다")
  void throwsForbiddenWhenCallerIsNotMember() {
    when(projectReader.workspaceIdOf(PROJECT_ID)).thenReturn(Optional.of(WORKSPACE_ID));
    when(workspaceMembershipReader.roleOf(WORKSPACE_ID, CALLER_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> signalListService.listUnprocessed(PROJECT_ID, CALLER_ID))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(CommonErrorCode.AUTH_FORBIDDEN);
  }

  @Test
  @DisplayName("멤버에게 미처리 Signal만 요약으로 반환한다")
  void returnsUnprocessedSummariesForMember() {
    when(projectReader.workspaceIdOf(PROJECT_ID)).thenReturn(Optional.of(WORKSPACE_ID));
    when(workspaceMembershipReader.roleOf(WORKSPACE_ID, CALLER_ID))
        .thenReturn(Optional.of(WorkspaceRole.MEMBER));
    UUID unprocessed =
        insertSignal("risk", "이탈 가능성", "완료율에 영향", "점검 제안", Instant.parse("2026-07-01T00:00:00Z"));
    UUID processed =
        insertSignal("decision", "처리됨", null, null, Instant.parse("2026-07-02T00:00:00Z"));
    insertAction(processed);

    List<SignalSummary> signals = signalListService.listUnprocessed(PROJECT_ID, CALLER_ID);

    assertThat(signals)
        .containsExactly(new SignalSummary(unprocessed, "risk", "이탈 가능성", "완료율에 영향", "점검 제안"));
  }

  @Test
  @DisplayName("커서 페이지로 범위 안 Signal을 최신순으로 나눠 반환한다")
  void pagesSignalsByCursor() {
    allowMember();
    UUID oldest = insertSignal("risk", "가장 오래됨", null, null, Instant.parse("2026-07-01T00:00:00Z"));
    UUID middle = insertSignal("decision", "중간", null, null, Instant.parse("2026-07-02T00:00:00Z"));
    UUID newest =
        insertSignal("question", "가장 최신", null, null, Instant.parse("2026-07-03T00:00:00Z"));

    SignalSummaryPage first =
        signalListService.listByCreatedRange(
            PROJECT_ID,
            CALLER_ID,
            List.of("risk", "decision", "question"),
            WIDE_FROM,
            WIDE_TO,
            null,
            2);
    assertThat(first.items()).extracting(SignalSummary::id).containsExactly(newest, middle);
    assertThat(first.nextCursor()).isNotNull();

    SignalSummaryPage second =
        signalListService.listByCreatedRange(
            PROJECT_ID,
            CALLER_ID,
            List.of("risk", "decision", "question"),
            WIDE_FROM,
            WIDE_TO,
            first.nextCursor(),
            2);
    assertThat(second.items()).extracting(SignalSummary::id).containsExactly(oldest);
    assertThat(second.nextCursor()).isNull();
  }

  @Test
  @DisplayName("생성 시각이 같으면 id 내림차순으로 페이지 순서를 고정한다")
  void breaksCreatedAtTieByIdAcrossPages() {
    allowMember();
    Instant sameInstant = Instant.parse("2026-07-01T00:00:00Z");
    UUID a = insertSignal("risk", "동률 A", null, null, sameInstant);
    UUID b = insertSignal("risk", "동률 B", null, null, sameInstant);
    // PostgreSQL uuid 정렬(바이트 순서)과 같은 canonical hex 내림차순이 기대 순서다.
    List<UUID> expected =
        Stream.of(a, b).sorted((x, y) -> y.toString().compareTo(x.toString())).toList();

    SignalSummaryPage first =
        signalListService.listByCreatedRange(
            PROJECT_ID, CALLER_ID, List.of("risk"), WIDE_FROM, WIDE_TO, null, 1);
    SignalSummaryPage second =
        signalListService.listByCreatedRange(
            PROJECT_ID, CALLER_ID, List.of("risk"), WIDE_FROM, WIDE_TO, first.nextCursor(), 1);

    assertThat(first.items()).extracting(SignalSummary::id).containsExactly(expected.get(0));
    assertThat(second.items()).extracting(SignalSummary::id).containsExactly(expected.get(1));
    assertThat(second.nextCursor()).isNull();
  }

  @Test
  @DisplayName("type 목록이 null이면 type을 가리지 않고 전체를 조회한다")
  void returnsAllTypesWhenTypesNull() {
    allowMember();
    UUID decision =
        insertSignal("decision", "결정", null, null, Instant.parse("2026-07-01T00:00:00Z"));
    UUID change = insertSignal("change", "VOC", null, null, Instant.parse("2026-07-02T00:00:00Z"));

    SignalSummaryPage page =
        signalListService.listByCreatedRange(
            PROJECT_ID, CALLER_ID, null, WIDE_FROM, WIDE_TO, null, 10);

    assertThat(page.items()).extracting(SignalSummary::id).containsExactly(change, decision);
  }

  @Test
  @DisplayName("범위 안이면 처리된 Signal도 담고, 넘긴 type만 담는다")
  void includesProcessedInRangeAndFiltersByType() {
    allowMember();
    UUID decision =
        insertSignal("decision", "결정", null, null, Instant.parse("2026-07-01T00:00:00Z"));
    insertSignal("change", "VOC", null, null, Instant.parse("2026-07-02T00:00:00Z"));
    UUID processed =
        insertSignal("decision", "전환됨", null, null, Instant.parse("2026-07-03T00:00:00Z"));
    insertAction(processed);

    SignalSummaryPage page =
        signalListService.listByCreatedRange(
            PROJECT_ID, CALLER_ID, List.of("decision"), WIDE_FROM, WIDE_TO, null, 10);

    // 처리(전환)된 decision도 범위 안이면 포함된다. 이게 시그널 탭 조회와의 핵심 차이다.
    assertThat(page.items()).extracting(SignalSummary::id).containsExactly(processed, decision);
    assertThat(page.nextCursor()).isNull();
  }

  @Test
  @DisplayName("범위 밖(어제, 내일)과 소프트 삭제된 Signal은 제외한다")
  void excludesOutOfRangeAndDeleted() {
    allowMember();
    Instant from = Instant.parse("2026-07-10T00:00:00Z");
    Instant toExclusive = Instant.parse("2026-07-11T00:00:00Z");
    insertSignal("risk", "어제", null, null, Instant.parse("2026-07-09T23:59:59Z"));
    UUID inRange = insertSignal("risk", "당일", null, null, Instant.parse("2026-07-10T12:00:00Z"));
    insertSignal("risk", "끝 경계", null, null, toExclusive); // 끝은 제외
    insertDeletedSignal("risk", "당일 삭제", Instant.parse("2026-07-10T13:00:00Z"));

    SignalSummaryPage page =
        signalListService.listByCreatedRange(
            PROJECT_ID, CALLER_ID, null, from, toExclusive, null, 10);

    assertThat(page.items()).extracting(SignalSummary::id).containsExactly(inRange);
  }

  @Test
  @DisplayName("페이지 크기가 상한(50)을 넘으면 상한으로 줄여 조회한다")
  void coercesPageSizeDownToMax() {
    allowMember();
    Instant base = Instant.parse("2026-07-01T00:00:00Z");
    for (int i = 0; i < 51; i++) {
      insertSignal("risk", "시그널 " + i, null, null, base.plusSeconds(i));
    }

    SignalSummaryPage page =
        signalListService.listByCreatedRange(
            PROJECT_ID, CALLER_ID, List.of("risk"), WIDE_FROM, WIDE_TO, null, 100);

    assertThat(page.items()).hasSize(50);
    assertThat(page.nextCursor()).isNotNull();
  }

  @Test
  @DisplayName("1 미만 limit은 COMMON_VALIDATION_FAILED로 실패한다")
  void throwsValidationFailedForLimitBelowOne() {
    allowMember();

    assertThatThrownBy(
            () ->
                signalListService.listByCreatedRange(
                    PROJECT_ID, CALLER_ID, List.of("risk"), WIDE_FROM, WIDE_TO, null, 0))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(CommonErrorCode.COMMON_VALIDATION_FAILED);
  }

  @Test
  @DisplayName("형식이 잘못된 커서는 COMMON_VALIDATION_FAILED로 실패한다")
  void throwsValidationFailedForMalformedCursor() {
    allowMember();

    assertThatThrownBy(
            () ->
                signalListService.listByCreatedRange(
                    PROJECT_ID, CALLER_ID, List.of("risk"), WIDE_FROM, WIDE_TO, "not-a-cursor", 3))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(CommonErrorCode.COMMON_VALIDATION_FAILED);
  }

  @Test
  @DisplayName("범위 안 Signal 개수를 type별로 집계하고 처리된 것도 포함한다")
  void countsByTypeInRangeIncludingProcessed() {
    allowMember();
    insertSignal("decision", "결정 1", null, null, Instant.parse("2026-07-01T00:00:00Z"));
    insertSignal("decision", "결정 2", null, null, Instant.parse("2026-07-02T00:00:00Z"));
    insertSignal("change", "VOC", null, null, Instant.parse("2026-07-03T00:00:00Z"));
    UUID processed = insertSignal("risk", "전환됨", null, null, Instant.parse("2026-07-04T00:00:00Z"));
    insertAction(processed);

    // 처리된 risk도 범위 안이면 개수에 포함된다.
    assertThat(signalListService.countByCreatedRange(PROJECT_ID, CALLER_ID, WIDE_FROM, WIDE_TO))
        .containsOnly(Map.entry("decision", 2L), Map.entry("change", 1L), Map.entry("risk", 1L));
  }

  private void allowMember() {
    when(projectReader.workspaceIdOf(PROJECT_ID)).thenReturn(Optional.of(WORKSPACE_ID));
    when(workspaceMembershipReader.roleOf(WORKSPACE_ID, CALLER_ID))
        .thenReturn(Optional.of(WorkspaceRole.MEMBER));
  }

  private UUID insertSignal(
      String type, String title, String impact, String minsuSuggestion, Instant createdAt) {
    UUID id = UUID.randomUUID();
    entityManager
        .getEntityManager()
        .createNativeQuery(
            "INSERT INTO signals (id, workspace_id, project_id, type, title, description, impact,"
                + " minsu_suggestion, created_at) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9)")
        .setParameter(1, id)
        .setParameter(2, WORKSPACE_ID)
        .setParameter(3, PROJECT_ID)
        .setParameter(4, type)
        .setParameter(5, title)
        .setParameter(6, "본문")
        .setParameter(7, impact)
        .setParameter(8, minsuSuggestion)
        .setParameter(9, createdAt)
        .executeUpdate();
    entityManager.clear();
    return id;
  }

  private void insertDeletedSignal(String type, String title, Instant createdAt) {
    UUID id = insertSignal(type, title, null, null, createdAt);
    entityManager
        .getEntityManager()
        .createNativeQuery("UPDATE signals SET deleted_at = ?1 WHERE id = ?2")
        .setParameter(1, createdAt)
        .setParameter(2, id)
        .executeUpdate();
    entityManager.clear();
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
}
