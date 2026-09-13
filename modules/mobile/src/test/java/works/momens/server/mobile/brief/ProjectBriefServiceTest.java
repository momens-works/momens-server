package works.momens.server.mobile.brief;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import works.momens.server.common.api.BusinessException;
import works.momens.server.common.api.CommonErrorCode;
import works.momens.server.mobile.MobileClock;
import works.momens.server.project.core.ProjectErrorCode;
import works.momens.server.project.core.ProjectReader;
import works.momens.server.project.core.ProjectSnapshot;
import works.momens.server.project.task.BoardTask;
import works.momens.server.project.task.TaskProgressReader;
import works.momens.server.project.task.TaskReader;
import works.momens.server.signal.SignalDigestReader;
import works.momens.server.signal.SignalListService;
import works.momens.server.signal.SignalSummary;
import works.momens.server.signal.SignalSummaryPage;
import works.momens.server.workspace.membership.WorkspaceMembershipReader;
import works.momens.server.workspace.membership.WorkspaceRole;

/**
 * 브리프 조합 규칙 검증. 도메인 모듈 public API는 각자 통합 테스트에서 검증하므로 여기서는 모두 mock으로 두고 조합 규칙만 확인합니다. project 확인과
 * 멤버십 검사 순서, 당일(KST) 범위로 signal을 조회하는지, 데이터 기반 필터 칩(구성과 라벨, 정렬), 페이지 기본값, 현재 우선순위 정렬과 상위 4개 제한이 그
 * 대상입니다. 하루 경계 계산은 고정 Clock으로 결정적으로 확인합니다.
 */
@ExtendWith(MockitoExtension.class)
class ProjectBriefServiceTest {

  @Mock private ProjectReader projectReader;
  @Mock private WorkspaceMembershipReader workspaceMembershipReader;
  @Mock private SignalListService signalListService;
  @Mock private SignalDigestReader signalDigestReader;
  @Mock private TaskReader taskReader;
  @Mock private TaskProgressReader taskProgressReader;
  private ProjectBriefService projectBriefService;

  private static final UUID PROJECT_ID = UUID.randomUUID();
  private static final UUID WORKSPACE_ID = UUID.randomUUID();
  private static final UUID CALLER_ID = UUID.randomUUID();
  private static final List<String> PRIORITY_STATUSES = List.of("todo", "in_progress");
  // 고정 시각 2026-07-10T05:00Z는 KST로 2026-07-10 14:00이다. 그날(KST)의 UTC 범위는 아래와 같다.
  private static final Clock FIXED_CLOCK =
      Clock.fixed(Instant.parse("2026-07-10T05:00:00Z"), ZoneOffset.UTC);
  private static final Instant TODAY_FROM = Instant.parse("2026-07-09T15:00:00Z");
  private static final Instant TODAY_TO = Instant.parse("2026-07-10T15:00:00Z");
  // FIXED_CLOCK 기준 오늘(KST). 커서에 실리는 기준일 앵커다.
  private static final LocalDate TODAY = LocalDate.of(2026, 7, 10);

  @BeforeEach
  void setUp() {
    projectBriefService =
        new ProjectBriefService(
            projectReader,
            workspaceMembershipReader,
            signalListService,
            signalDigestReader,
            taskReader,
            taskProgressReader,
            new MobileClock(FIXED_CLOCK));
  }

  @Test
  void getBriefThrowsProjectNotFoundWhenProjectMissing() {
    when(projectReader.findSnapshot(PROJECT_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> projectBriefService.getBrief(PROJECT_ID, CALLER_ID))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(ProjectErrorCode.PROJECT_NOT_FOUND);
  }

  @Test
  void getBriefThrowsForbiddenWhenCallerIsNotWorkspaceMember() {
    when(projectReader.findSnapshot(PROJECT_ID)).thenReturn(Optional.of(snapshot()));
    when(workspaceMembershipReader.roleOf(WORKSPACE_ID, CALLER_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> projectBriefService.getBrief(PROJECT_ID, CALLER_ID))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(CommonErrorCode.AUTH_FORBIDDEN);
  }

  @Test
  void getBriefCombinesSnapshotFiltersItemsAndPriorities() {
    ProjectSnapshot snapshot = snapshot();
    UUID signalId = UUID.randomUUID();
    UUID taskId = UUID.randomUUID();
    when(projectReader.findSnapshot(PROJECT_ID)).thenReturn(Optional.of(snapshot));
    when(taskProgressReader.progressOf(PROJECT_ID)).thenReturn(OptionalInt.of(64));
    when(workspaceMembershipReader.roleOf(WORKSPACE_ID, CALLER_ID))
        .thenReturn(Optional.of(WorkspaceRole.MEMBER));
    // change도 all 개수에 포함되고, 칩과 items에도 나온다.
    when(signalListService.countByCreatedRange(PROJECT_ID, CALLER_ID, TODAY_FROM, TODAY_TO))
        .thenReturn(Map.of("decision", 2L, "risk", 1L, "question", 2L, "change", 7L));
    // filter=all은 type으로 필터링하지 않으므로 null을 전달합니다. 첫 조회는 한 페이지(20)를 미리 가져옵니다.
    when(signalListService.listByCreatedRange(
            eq(PROJECT_ID),
            eq(CALLER_ID),
            isNull(),
            eq(TODAY_FROM),
            eq(TODAY_TO),
            isNull(),
            eq(20)))
        .thenReturn(
            new SignalSummaryPage(
                List.of(new SignalSummary(signalId, "change", "권한 요청 반복 문의", null, null)),
                "cursor-1"));
    when(taskReader.listTasksByStatus(PROJECT_ID, PRIORITY_STATUSES))
        .thenReturn(List.of(task(taskId, "이메일 회원가입 완료율 개선", "high", "2026-07-01T00:00:00Z")));

    MobileBrief brief = projectBriefService.getBrief(PROJECT_ID, CALLER_ID);

    assertThat(brief.project()).isEqualTo(snapshot);
    // 진행률은 project 모듈의 task 경계가 계산한 값을 그대로 싣는다.
    assertThat(brief.progress()).isEqualTo(64);
    // All이 맨 앞(전체 12), 나머지는 라벨 글자수 오름차순과 알파벳순(Risk, Change, Decision, Question).
    assertThat(brief.filters())
        .containsExactly(
            new MobileBrief.FilterCount("all", "All", 12L),
            new MobileBrief.FilterCount("risk", "Risk", 1L),
            new MobileBrief.FilterCount("change", "Change", 7L),
            new MobileBrief.FilterCount("decision", "Decision", 2L),
            new MobileBrief.FilterCount("question", "Question", 2L));
    assertThat(brief.items())
        .containsExactly(new MobileBrief.SignalItem(signalId, "change", "권한 요청 반복 문의"));
    // next_cursor는 signal 커서를 오늘 앵커와 함께 감싼 값이다.
    assertThat(BriefSignalCursor.decode(brief.nextCursor()))
        .isEqualTo(new BriefSignalCursor(TODAY, "cursor-1"));
    assertThat(brief.priorities())
        .containsExactly(new MobileBrief.Priority(1, "이메일 회원가입 완료율 개선", taskId));
  }

  @Test
  void getBriefReadsDigestWithTheSameDayRangeAsSignals() {
    stubBriefBase();
    when(signalDigestReader.findByCreatedRange(PROJECT_ID, CALLER_ID, TODAY_FROM, TODAY_TO))
        .thenReturn(Optional.of("오늘은 회원가입 권한 요청 이탈이 주요 신호로 올라왔습니다."));

    MobileBrief brief = projectBriefService.getBrief(PROJECT_ID, CALLER_ID);

    assertThat(brief.signalDigest()).isEqualTo("오늘은 회원가입 권한 요청 이탈이 주요 신호로 올라왔습니다.");
    // 문단을 시그널과 같은 범위(TODAY_FROM, TODAY_TO)로 조회해야 어제 문단이 오늘 목록 위에 붙지 않는다.
    // 위 stub이 그 범위로 걸려 있어, 다른 범위로 조회하면 문단이 null이 되어 이 단언이 깨진다.
    verify(signalDigestReader).findByCreatedRange(PROJECT_ID, CALLER_ID, TODAY_FROM, TODAY_TO);
  }

  @Test
  void getBriefReturnsNullDigestWhenMinsuHasNotProducedOne() {
    stubBriefBase();
    when(signalDigestReader.findByCreatedRange(PROJECT_ID, CALLER_ID, TODAY_FROM, TODAY_TO))
        .thenReturn(Optional.empty());

    MobileBrief brief = projectBriefService.getBrief(PROJECT_ID, CALLER_ID);

    assertThat(brief.signalDigest()).isNull();
  }

  @Test
  void getBriefOmitsChipsForTypesWithNoSignalsToday() {
    stubBriefBase();
    // 당일 목록에 decision만 있으면 칩은 All과 Decision 둘뿐이다(개수 0인 type은 칩이 없다).
    when(signalListService.countByCreatedRange(PROJECT_ID, CALLER_ID, TODAY_FROM, TODAY_TO))
        .thenReturn(Map.of("decision", 3L));

    MobileBrief brief = projectBriefService.getBrief(PROJECT_ID, CALLER_ID);

    assertThat(brief.filters())
        .containsExactly(
            new MobileBrief.FilterCount("all", "All", 3L),
            new MobileBrief.FilterCount("decision", "Decision", 3L));
  }

  @Test
  void getBriefRanksPrioritiesByPriorityThenOldestCreationAndLimitsToFour() {
    stubBriefBase();
    UUID urgentOld = UUID.randomUUID();
    UUID highNew = UUID.randomUUID();
    UUID mediumOld = UUID.randomUUID();
    UUID mediumNew = UUID.randomUUID();
    UUID low = UUID.randomUUID();
    // 저장 priority가 urgent면 high와 같은 순위이고, 같은 순위 안에서는 생성이 오래된 순이다.
    when(taskReader.listTasksByStatus(PROJECT_ID, PRIORITY_STATUSES))
        .thenReturn(
            List.of(
                task(mediumNew, "중간 최신", "medium", "2026-07-04T00:00:00Z"),
                task(low, "낮음", "low", "2026-07-01T00:00:00Z"),
                task(highNew, "높음 최신", "high", "2026-07-03T00:00:00Z"),
                task(urgentOld, "긴급 오래됨", "urgent", "2026-07-02T00:00:00Z"),
                task(mediumOld, "중간 오래됨", "medium", "2026-07-01T00:00:00Z")));

    List<MobileBrief.Priority> priorities =
        projectBriefService.getBrief(PROJECT_ID, CALLER_ID).priorities();

    assertThat(priorities)
        .containsExactly(
            new MobileBrief.Priority(1, "긴급 오래됨", urgentOld),
            new MobileBrief.Priority(2, "높음 최신", highNew),
            new MobileBrief.Priority(3, "중간 오래됨", mediumOld),
            new MobileBrief.Priority(4, "중간 최신", mediumNew));
  }

  @Test
  void getBriefRanksUrgentAtTheSameLevelAsHigh() {
    stubBriefBase();
    UUID highOld = UUID.randomUUID();
    UUID urgentNew = UUID.randomUUID();
    // urgent가 high보다 높은 순위였다면 생성 시각과 무관하게 urgent가 먼저 온다. 같은 순위이므로
    // 생성이 오래된 high가 먼저 와야 한다.
    when(taskReader.listTasksByStatus(PROJECT_ID, PRIORITY_STATUSES))
        .thenReturn(
            List.of(
                task(urgentNew, "긴급 최신", "urgent", "2026-07-02T00:00:00Z"),
                task(highOld, "높음 오래됨", "high", "2026-07-01T00:00:00Z")));

    List<MobileBrief.Priority> priorities =
        projectBriefService.getBrief(PROJECT_ID, CALLER_ID).priorities();

    assertThat(priorities)
        .containsExactly(
            new MobileBrief.Priority(1, "높음 오래됨", highOld),
            new MobileBrief.Priority(2, "긴급 최신", urgentNew));
  }

  @Test
  void getSignalSummaryPagePassesSingleTypeForTypeFilter() {
    UUID signalId = UUID.randomUUID();
    // filter=change는 change 하나만 type으로 넘긴다.
    when(signalListService.listByCreatedRange(
            eq(PROJECT_ID),
            eq(CALLER_ID),
            eq(List.of("change")),
            eq(TODAY_FROM),
            eq(TODAY_TO),
            eq("cursor-1"),
            eq(20)))
        .thenReturn(
            new SignalSummaryPage(
                List.of(new SignalSummary(signalId, "change", "VOC 문의", null, null)), null));

    // 클라이언트가 되돌리는 커서는 오늘 앵커로 감싼 값이고, 서비스는 안쪽 signal 커서만 signal에 넘긴다.
    MobileBriefSignalPage page =
        projectBriefService.getSignalSummaryPage(
            PROJECT_ID, CALLER_ID, "change", BriefSignalCursor.encode(TODAY, "cursor-1"), null);

    assertThat(page.items())
        .containsExactly(new MobileBrief.SignalItem(signalId, "change", "VOC 문의"));
    assertThat(page.nextCursor()).isNull();
  }

  @Test
  void getSignalSummaryPageUsesCursorAnchorRatherThanRequestTime() {
    // 커서에 실린 기준일(어제)로 창을 잡고 요청 시각의 오늘(FIXED_CLOCK)로 밀리지 않는다.
    LocalDate yesterday = LocalDate.of(2026, 7, 9);
    Instant yesterdayFrom = Instant.parse("2026-07-08T15:00:00Z");
    Instant yesterdayTo = Instant.parse("2026-07-09T15:00:00Z");
    when(signalListService.listByCreatedRange(
            eq(PROJECT_ID),
            eq(CALLER_ID),
            isNull(),
            eq(yesterdayFrom),
            eq(yesterdayTo),
            eq("inner"),
            eq(20)))
        .thenReturn(new SignalSummaryPage(List.of(), null));

    MobileBriefSignalPage page =
        projectBriefService.getSignalSummaryPage(
            PROJECT_ID, CALLER_ID, "all", BriefSignalCursor.encode(yesterday, "inner"), null);

    assertThat(page.items()).isEmpty();
  }

  @Test
  void getSignalSummaryPagePassesNullTypesForAllFilter() {
    // filter=all이나 빈 값이면 type을 가리지 않으므로 null을 넘긴다.
    when(signalListService.listByCreatedRange(
            eq(PROJECT_ID),
            eq(CALLER_ID),
            isNull(),
            eq(TODAY_FROM),
            eq(TODAY_TO),
            isNull(),
            eq(20)))
        .thenReturn(new SignalSummaryPage(List.of(), null));

    assertThat(projectBriefService.getSignalSummaryPage(PROJECT_ID, CALLER_ID, "all", null, null))
        .isNotNull();
    assertThat(projectBriefService.getSignalSummaryPage(PROJECT_ID, CALLER_ID, null, null, null))
        .isNotNull();
  }

  @Test
  void getSignalSummaryPageTreatsZeroLimitAsDefault() {
    // AIP-158과 명세대로 0은 에러가 아니라 더보기 기본값 20으로 조회한다.
    when(signalListService.listByCreatedRange(
            eq(PROJECT_ID),
            eq(CALLER_ID),
            eq(List.of("decision")),
            eq(TODAY_FROM),
            eq(TODAY_TO),
            isNull(),
            eq(20)))
        .thenReturn(new SignalSummaryPage(List.of(), null));

    MobileBriefSignalPage page =
        projectBriefService.getSignalSummaryPage(PROJECT_ID, CALLER_ID, "decision", null, 0);

    assertThat(page.items()).isEmpty();
  }

  @Test
  void getSignalSummaryPageThrowsValidationFailedForNegativeLimit() {
    assertThatThrownBy(
            () -> projectBriefService.getSignalSummaryPage(PROJECT_ID, CALLER_ID, "all", null, -1))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(CommonErrorCode.COMMON_VALIDATION_FAILED);
  }

  private void stubBriefBase() {
    when(projectReader.findSnapshot(PROJECT_ID)).thenReturn(Optional.of(snapshot()));
    lenient().when(taskProgressReader.progressOf(PROJECT_ID)).thenReturn(OptionalInt.of(64));
    when(workspaceMembershipReader.roleOf(WORKSPACE_ID, CALLER_ID))
        .thenReturn(Optional.of(WorkspaceRole.MEMBER));
    lenient()
        .when(signalListService.countByCreatedRange(PROJECT_ID, CALLER_ID, TODAY_FROM, TODAY_TO))
        .thenReturn(Map.of());
    lenient()
        .when(
            signalListService.listByCreatedRange(
                eq(PROJECT_ID),
                eq(CALLER_ID),
                isNull(),
                eq(TODAY_FROM),
                eq(TODAY_TO),
                isNull(),
                eq(20)))
        .thenReturn(new SignalSummaryPage(List.of(), null));
  }

  private static BoardTask task(UUID id, String title, String priority, String createdAt) {
    return new BoardTask(id, title, "todo", priority, "pm", Instant.parse(createdAt));
  }

  private static ProjectSnapshot snapshot() {
    return new ProjectSnapshot(
        PROJECT_ID,
        WORKSPACE_ID,
        "Q2 Activation Readiness",
        LocalDate.of(2026, 6, 30),
        "목표일까지 Q2 Activation Readiness 범위의 회원 가입 MVP를 안정적으로 릴리즈한다.");
  }
}
