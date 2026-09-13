package works.momens.server.mobile.brief;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import works.momens.server.common.api.BusinessException;
import works.momens.server.common.api.CommonErrorCode;
import works.momens.server.mobile.MobileClock;
import works.momens.server.mobile.MobilePriority;
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

/**
 * 모바일 브리프 표면의 조합 서비스. project(스냅샷, 태스크), signal(당일 시그널 요약), workspace(멤버십) public API를 조합하고 도메인 정책을
 * 소유하지 않습니다.
 *
 * <p>project 조회 결과가 workspace id를 포함하므로(태스크 상세와 같은 방식) project가 있는지 확인한 뒤 workspace를 따로 조회하지 않고 바로
 * 멤버십을 검사합니다. signal 목록 서비스도 자체적으로 접근을 검사해 브리프의 검사와 일부 겹치지만, 단순 조회 수준이라 그대로 두었습니다.
 *
 * <p>브리프는 오늘의 브리프라 그날 생성된 시그널을 처리 여부와 무관하게 봅니다(MOM-81). 시그널 요약 필터 칩(당일 시그널의 type으로 데이터 기반 구성, 라벨과
 * 정렬), 오늘의 하루 경계({@link BriefDay}), 페이지 기본 크기, 현재 우선순위 구성(후보 상태와 정렬, 상위 4개)은 모바일 조합 규칙이므로 이 서비스와
 * {@link SignalTypeLabel}, {@link MobilePriority}가 소유합니다.
 */
@Service
@RequiredArgsConstructor
class ProjectBriefService {

  /**
   * 시그널 요약 한 페이지의 기본 크기입니다.
   *
   * <p>브리프 조회는 첫 페이지를 이 크기로 반환하고, 더보기 엔드포인트도 {@code limit}이 없으면 이 값을 사용합니다.
   *
   * <p>접힌 상태에서는 클라이언트가 이 중 최신 3개만 노출합니다. 더보기를 누르면 이미 받은 항목을 먼저 펼쳐 보여주고, 모두 표시한 뒤에는 {@code
   * next_cursor}로 다음 페이지를 조회해 무한 스크롤을 이어갑니다(2026-07-10 화면설계서). 최신 3개만 노출하는 규칙은 화면 정책이므로, 서버는 응답을
   * 자르지 않고 클라이언트가 처리합니다.
   *
   * <p>이 값은 화면의 기본 페이지 크기를 정하는 UX 설정이고, {@code MAX_PAGE_SIZE}는 서버 보호를 위한 상한입니다. 두 값은 목적이 다르므로 별도로
   * 관리합니다. 현재 기획 확정값은 20입니다(2026-07-16).
   */
  private static final int SIGNAL_SUMMARY_PAGE_SIZE = 20;

  /** 타입 칩 정렬. 라벨 글자수 오름차순, 같으면 라벨 알파벳순입니다(2026-07-10 기획 확정). All 칩은 정렬에서 빼고 맨 앞에 둡니다. */
  private static final Comparator<MobileBrief.FilterCount> CHIP_ORDER =
      Comparator.comparingInt((MobileBrief.FilterCount chip) -> chip.label().length())
          .thenComparing(MobileBrief.FilterCount::label);

  /** 현재 우선순위 최대 개수. 화면의 상위 4개 표기와 같습니다(2026-07-10 화면설계서). */
  private static final int PRIORITY_LIMIT = 4;

  /** 현재 우선순위 후보 상태. 진행 중인 todo와 in_progress만 담고 backlog와 완료 상태는 제외합니다(2026-07-10 기획 확정). */
  private static final List<String> PRIORITY_STATUSES = List.of("todo", "in_progress");

  /**
   * 우선순위 정렬. priority 높은 순이고, 같으면 생성 오래된 순, 생성 시각까지 같으면 id 순으로 고정합니다. id 비교는 canonical hex 문자열로
   * 합니다. PostgreSQL uuid 정렬(바이트 순서)과 같아서 저장소 정렬과 어긋나지 않습니다(signal 커서와 같은 이유).
   */
  private static final Comparator<BoardTask> PRIORITY_ORDER =
      Comparator.comparing((BoardTask task) -> MobilePriority.fromStored(task.priority()))
          .thenComparing(BoardTask::createdAt)
          .thenComparing(task -> task.id().toString());

  private final ProjectReader projectReader;
  private final WorkspaceMembershipReader workspaceMembershipReader;
  private final SignalListService signalListService;
  private final SignalDigestReader signalDigestReader;
  private final TaskReader taskReader;
  private final TaskProgressReader taskProgressReader;
  private final MobileClock mobileClock;

  @Transactional(readOnly = true)
  public MobileBrief getBrief(UUID projectId, UUID userId) {
    ProjectSnapshot snapshot =
        projectReader
            .findSnapshot(projectId)
            .orElseThrow(
                () ->
                    new BusinessException(
                        ProjectErrorCode.PROJECT_NOT_FOUND,
                        Map.of("project_id", projectId.toString())));
    if (workspaceMembershipReader.roleOf(snapshot.workspaceId(), userId).isEmpty()) {
      throw new BusinessException(
          CommonErrorCode.AUTH_FORBIDDEN, Map.of("project_id", projectId.toString()));
    }
    // 진행률 계산 책임은 project Gradle 모듈 안의 task 경계에 있다. 여기서는 계산된 값을 그대로 사용한다.
    int progress =
        taskProgressReader
            .progressOf(projectId)
            .orElseThrow(
                () ->
                    new BusinessException(
                        ProjectErrorCode.PROJECT_NOT_FOUND,
                        Map.of("project_id", projectId.toString())));
    // 브리프는 오늘의 브리프라 그날 온 시그널을 처리 여부와 무관하게 센다(MOM-81).
    LocalDate anchor = BriefDay.today(mobileClock.clock());
    BriefDay.Range today = BriefDay.rangeOf(anchor);
    Map<String, Long> countsByType =
        signalListService.countByCreatedRange(projectId, userId, today.from(), today.toExclusive());
    // filter=all은 type을 가리지 않고 전체를 조회하므로 null을 넘긴다. change도 items에 포함된다.
    SignalSummaryPage firstPage =
        signalListService.listByCreatedRange(
            projectId,
            userId,
            null,
            today.from(),
            today.toExclusive(),
            null,
            SIGNAL_SUMMARY_PAGE_SIZE);
    // 요약 문단은 시그널과 같은 하루 범위로 조회한다. 기준이 갈리면 어제 문단이 오늘 목록 위에 붙는다.
    String digest =
        signalDigestReader
            .findByCreatedRange(projectId, userId, today.from(), today.toExclusive())
            .orElse(null);
    return new MobileBrief(
        snapshot,
        progress,
        digest,
        toFilterCounts(countsByType),
        toItems(firstPage.items()),
        nextCursor(anchor, firstPage),
        toPriorities(taskReader.listTasksByStatus(projectId, PRIORITY_STATUSES)));
  }

  @Transactional(readOnly = true)
  public MobileBriefSignalPage getSignalSummaryPage(
      UUID projectId, UUID userId, String filterKey, String cursor, Integer limit) {
    // filter는 열린 어휘라 all이나 빈 값이면 전체, 그 외는 그대로 type으로 넘긴다. 알려지지 않은 type은 매칭이 없어 빈 목록이 된다.
    List<String> types =
        filterKey == null || filterKey.isBlank() || SignalTypeLabel.ALL_KEY.equals(filterKey)
            ? null
            : List.of(filterKey);
    // 커서가 있으면 첫 페이지에서 정한 기준일을 그대로 쓰고, 없으면(직접 첫 페이지 조회) 오늘로 앵커를 잡는다.
    // 이렇게 하면 페이지네이션 도중 자정을 넘겨도 창이 밀리지 않는다.
    boolean hasCursor = cursor != null && !cursor.isBlank();
    BriefSignalCursor position = hasCursor ? BriefSignalCursor.decode(cursor) : null;
    LocalDate anchor = hasCursor ? position.anchor() : BriefDay.today(mobileClock.clock());
    BriefDay.Range window = BriefDay.rangeOf(anchor);
    SignalSummaryPage page =
        signalListService.listByCreatedRange(
            projectId,
            userId,
            types,
            window.from(),
            window.toExclusive(),
            hasCursor ? position.signalCursor() : null,
            resolvePageSize(limit));
    return new MobileBriefSignalPage(toItems(page.items()), nextCursor(anchor, page));
  }

  /** signal 커서를 기준일 앵커와 함께 감싼다. 다음 페이지가 없으면 null이다. */
  private static String nextCursor(LocalDate anchor, SignalSummaryPage page) {
    return page.nextCursor() == null ? null : BriefSignalCursor.encode(anchor, page.nextCursor());
  }

  private static int resolvePageSize(Integer limit) {
    // limit이 없거나 0이면 더보기 기본 페이지 크기를 사용합니다. 음수만 잘못된 요청으로 처리합니다(AIP-158).
    if (limit == null || limit == 0) {
      return SIGNAL_SUMMARY_PAGE_SIZE;
    }
    if (limit < 0) {
      throw new BusinessException(
          CommonErrorCode.COMMON_VALIDATION_FAILED, Map.of("limit", limit.toString()));
    }
    return limit;
  }

  private static List<MobileBrief.FilterCount> toFilterCounts(Map<String, Long> countsByType) {
    // 당일 목록에 있는 type만 칩으로 만든다(개수 0인 type은 칩이 없다). All 칩은 전체 합으로 맨 앞에 고정한다.
    MobileBrief.FilterCount all =
        new MobileBrief.FilterCount(
            SignalTypeLabel.ALL_KEY,
            SignalTypeLabel.ALL_LABEL,
            countsByType.values().stream().mapToLong(Long::longValue).sum());
    List<MobileBrief.FilterCount> typeChips =
        countsByType.entrySet().stream()
            .map(
                entry ->
                    new MobileBrief.FilterCount(
                        entry.getKey(), SignalTypeLabel.of(entry.getKey()), entry.getValue()))
            .sorted(CHIP_ORDER)
            .toList();
    return Stream.concat(Stream.of(all), typeChips.stream()).toList();
  }

  private static List<MobileBrief.SignalItem> toItems(List<SignalSummary> summaries) {
    return summaries.stream()
        .map(summary -> new MobileBrief.SignalItem(summary.id(), summary.type(), summary.title()))
        .toList();
  }

  private static List<MobileBrief.Priority> toPriorities(List<BoardTask> tasks) {
    List<BoardTask> ordered = tasks.stream().sorted(PRIORITY_ORDER).limit(PRIORITY_LIMIT).toList();
    return IntStream.range(0, ordered.size())
        .mapToObj(
            index ->
                new MobileBrief.Priority(
                    index + 1, ordered.get(index).title(), ordered.get(index).id()))
        .toList();
  }
}
