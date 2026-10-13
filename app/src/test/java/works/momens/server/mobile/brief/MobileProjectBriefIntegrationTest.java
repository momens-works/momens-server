package works.momens.server.mobile.brief;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import works.momens.server.auth.AccessTokenTestFactory;
import works.momens.server.common.test.AbstractPostgresIntegrationTest;
import works.momens.server.mobile.MobileClock;
import works.momens.server.user.UserProfile;
import works.momens.server.user.UserService;

/** 브리프의 오늘을 고정 Clock으로 제어하며 KST 경계 밖·처리된 시그널을 함께 넣어 집계 범위를 검증합니다. */
@SpringBootTest
@AutoConfigureMockMvc
class MobileProjectBriefIntegrationTest extends AbstractPostgresIntegrationTest {

  // 고정 시각 2026-07-10T12:00Z는 KST로 2026-07-10 21:00이다. 그날(KST) 범위는 UTC 07-09 15:00 이상 07-10 15:00
  // 미만.
  private static final Instant FIXED_NOW = Instant.parse("2026-07-10T12:00:00Z");

  @Autowired private MockMvc mockMvc;
  @Autowired private AccessTokenTestFactory accessTokens;
  @Autowired private UserService userService;
  @Autowired private JdbcTemplate jdbcTemplate;
  @MockitoBean private MobileClock mobileClock;

  @BeforeEach
  void fixClock() {
    when(mobileClock.clock()).thenReturn(Clock.fixed(FIXED_NOW, ZoneOffset.UTC));
  }

  @Test
  void returnsProjectSnapshotForMember() throws Exception {
    UserProfile jinsu = userService.findOrCreate("brief-it-jinsu@momens.works", "신진수", null);
    UUID workspace = insertWorkspace("brief-snapshot");
    addMember(workspace, jinsu.id(), "owner");
    UUID project =
        insertProject(
            workspace,
            jinsu.id(),
            "Q2 Activation Readiness",
            LocalDate.of(2026, 6, 30),
            64,
            "목표일까지 Q2 Activation Readiness 범위의 회원 가입 MVP를 안정적으로 릴리즈한다.");

    mockMvc
        .perform(
            get("/api/mobile/projects/{projectId}/brief", project)
                .header("Authorization", "Bearer " + accessTokens.issueAccessToken(jinsu.id()))
                .header("API-Version", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.project.id").value(project.toString()))
        .andExpect(jsonPath("$.project.name").value("Q2 Activation Readiness"))
        .andExpect(jsonPath("$.project.target_date").value("2026-06-30"))
        // progress 컬럼에 64가 저장돼 있어도 응답은 태스크에서 계산한다. 태스크가 없으므로 0이다.
        .andExpect(jsonPath("$.project.progress").value(0))
        .andExpect(
            jsonPath("$.project.summary")
                .value("목표일까지 Q2 Activation Readiness 범위의 회원 가입 MVP를 안정적으로 릴리즈한다."))
        // 시그널이 없으면 개수는 0, 목록은 빈 배열, 다음 커서와 요약 문단은 null로 항상 포함된다.
        .andExpect(jsonPath("$.signal_summary.summary", nullValue()))
        .andExpect(jsonPath("$.signal_summary.filters[0].key").value("all"))
        .andExpect(jsonPath("$.signal_summary.filters[0].count").value(0))
        .andExpect(jsonPath("$.signal_summary.items.length()").value(0))
        .andExpect(jsonPath("$.signal_summary.next_cursor", nullValue()))
        .andExpect(jsonPath("$.priorities.length()").value(0));
  }

  @Test
  void ranksPrioritiesByPriorityThenOldestCreationAndLimitsToFour() throws Exception {
    UserProfile jinsu =
        userService.findOrCreate("brief-it-priority-jinsu@momens.works", "신진수", null);
    UUID workspace = insertWorkspace("brief-priority");
    addMember(workspace, jinsu.id(), "owner");
    UUID project = insertProject(workspace, jinsu.id(), "brief-priority-project", null, 0, null);
    UUID urgentOld =
        insertTask(workspace, project, "긴급 오래됨", "in_progress", "urgent", "2026-07-01T00:00:00Z");
    UUID highNew = insertTask(workspace, project, "높음 최신", "todo", "high", "2026-07-03T00:00:00Z");
    UUID mediumOld =
        insertTask(workspace, project, "중간 오래됨", "in_progress", "medium", "2026-07-01T00:00:00Z");
    UUID mediumNew =
        insertTask(workspace, project, "중간 최신", "todo", "medium", "2026-07-02T00:00:00Z");
    insertTask(workspace, project, "낮음 잘림", "todo", "low", "2026-07-01T00:00:00Z");
    // backlog와 done, cancelled는 우선순위 후보에서 빠져야 한다. backlog는 urgent라도 제외된다.
    insertTask(workspace, project, "백로그 제외", "backlog", "urgent", "2026-07-01T00:00:00Z");
    insertTask(workspace, project, "완료됨", "done", "high", "2026-07-01T00:00:00Z");
    insertTask(workspace, project, "취소됨", "cancelled", "high", "2026-07-01T00:00:00Z");

    mockMvc
        .perform(
            get("/api/mobile/projects/{projectId}/brief", project)
                .header("Authorization", "Bearer " + accessTokens.issueAccessToken(jinsu.id()))
                .header("API-Version", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.priorities.length()").value(4))
        .andExpect(jsonPath("$.priorities[0].rank").value(1))
        .andExpect(jsonPath("$.priorities[0].title").value("긴급 오래됨"))
        .andExpect(jsonPath("$.priorities[0].task_id").value(urgentOld.toString()))
        .andExpect(jsonPath("$.priorities[1].task_id").value(highNew.toString()))
        .andExpect(jsonPath("$.priorities[2].task_id").value(mediumOld.toString()))
        .andExpect(jsonPath("$.priorities[3].rank").value(4))
        .andExpect(jsonPath("$.priorities[3].task_id").value(mediumNew.toString()));
  }

  @Test
  void returnsTodaySignalsIncludingConvertedAndExcludingOtherDays() throws Exception {
    UserProfile jinsu = userService.findOrCreate("brief-it-signal-jinsu@momens.works", "신진수", null);
    UUID workspace = insertWorkspace("brief-signal");
    addMember(workspace, jinsu.id(), "owner");
    UUID project = insertProject(workspace, jinsu.id(), "brief-signal-project", null, 0, null);
    // 모두 당일(KST 2026-07-10) 범위 안이다.
    insertSignal(workspace, project, "decision", "소셜 로그인은 MVP 범위에서 제외", "2026-07-10T00:00:00Z");
    insertSignal(workspace, project, "decision", "회원가입 MVP 범위 1차 확정", "2026-07-10T01:00:00Z");
    UUID converted =
        insertSignal(
            workspace, project, "risk", "Android 13+ 권한 요청 플로우 이탈 가능성", "2026-07-10T02:00:00Z");
    insertSignal(workspace, project, "question", "온보딩 단계 수 확정 필요", "2026-07-10T03:00:00Z");
    insertSignal(workspace, project, "question", "권한 요청 문구 결정 필요", "2026-07-10T04:00:00Z");
    UUID voc = insertSignal(workspace, project, "change", "권한 요청 반복 문의", "2026-07-10T05:00:00Z");
    // risk 하나를 태스크로 전환해도 당일 집계에는 그대로 남는다(MOM-81 핵심).
    insertAction(workspace, converted, jinsu.id());
    // 어제(범위 밖)와 당일에 소프트 삭제된 시그널은 집계에서 빠진다.
    insertSignal(workspace, project, "risk", "어제 온 시그널", "2026-07-09T00:00:00Z");
    insertDeletedSignal(workspace, project, "change", "당일 삭제", "2026-07-10T06:00:00Z");

    mockMvc
        .perform(
            get("/api/mobile/projects/{projectId}/brief", project)
                .header("Authorization", "Bearer " + accessTokens.issueAccessToken(jinsu.id()))
                .header("API-Version", "1"))
        .andExpect(status().isOk())
        // 전환된 risk까지 그대로 세서 전체 6. 나머지는 라벨 글자수 오름차순과 알파벳순(Risk, Change, Decision, Question).
        .andExpect(jsonPath("$.signal_summary.filters.length()").value(5))
        .andExpect(jsonPath("$.signal_summary.filters[0].key").value("all"))
        .andExpect(jsonPath("$.signal_summary.filters[0].count").value(6))
        .andExpect(jsonPath("$.signal_summary.filters[1].key").value("risk"))
        .andExpect(jsonPath("$.signal_summary.filters[1].label").value("Risk"))
        .andExpect(jsonPath("$.signal_summary.filters[1].count").value(1))
        .andExpect(jsonPath("$.signal_summary.filters[2].key").value("change"))
        .andExpect(jsonPath("$.signal_summary.filters[2].label").value("Change"))
        .andExpect(jsonPath("$.signal_summary.filters[2].count").value(1))
        .andExpect(jsonPath("$.signal_summary.filters[3].key").value("decision"))
        .andExpect(jsonPath("$.signal_summary.filters[3].label").value("Decision"))
        .andExpect(jsonPath("$.signal_summary.filters[3].count").value(2))
        .andExpect(jsonPath("$.signal_summary.filters[4].key").value("question"))
        .andExpect(jsonPath("$.signal_summary.filters[4].count").value(2))
        .andExpect(jsonPath("$.signal_summary.items.length()").value(6))
        .andExpect(jsonPath("$.signal_summary.items[0].id").value(voc.toString()))
        .andExpect(jsonPath("$.signal_summary.items[0].type").value("change"))
        .andExpect(jsonPath("$.signal_summary.items[0].title").value("권한 요청 반복 문의"))
        .andExpect(jsonPath("$.signal_summary.items[1].title").value("권한 요청 문구 결정 필요"))
        .andExpect(jsonPath("$.signal_summary.items[2].title").value("온보딩 단계 수 확정 필요"))
        .andExpect(jsonPath("$.signal_summary.next_cursor", nullValue()));
  }

  @Test
  void paginatesSignalSummaryWithCursorAndFilter() throws Exception {
    UserProfile jinsu = userService.findOrCreate("brief-it-page-jinsu@momens.works", "신진수", null);
    UUID workspace = insertWorkspace("brief-page");
    addMember(workspace, jinsu.id(), "owner");
    UUID project = insertProject(workspace, jinsu.id(), "brief-page-project", null, 0, null);
    UUID oldest = insertSignal(workspace, project, "decision", "가장 오래된 결정", "2026-07-10T01:00:00Z");
    insertSignal(workspace, project, "risk", "리스크", "2026-07-10T02:00:00Z");
    insertSignal(workspace, project, "question", "질문", "2026-07-10T03:00:00Z");
    insertSignal(workspace, project, "decision", "최신 결정", "2026-07-10T04:00:00Z");

    // limit=3으로 첫 페이지 3개를 받고, next_cursor로 나머지 1개를 이어서 조회한다.
    String firstPageBody =
        mockMvc
            .perform(
                get("/api/mobile/projects/{projectId}/brief/signal-summary", project)
                    .param("limit", "3")
                    .header("Authorization", "Bearer " + accessTokens.issueAccessToken(jinsu.id()))
                    .header("API-Version", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(3))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String nextCursor = JsonPath.read(firstPageBody, "$.next_cursor");

    mockMvc
        .perform(
            get("/api/mobile/projects/{projectId}/brief/signal-summary", project)
                .param("cursor", nextCursor)
                .param("limit", "3")
                .header("Authorization", "Bearer " + accessTokens.issueAccessToken(jinsu.id()))
                .header("API-Version", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].id").value(oldest.toString()))
        .andExpect(jsonPath("$.next_cursor", nullValue()));

    mockMvc
        .perform(
            get("/api/mobile/projects/{projectId}/brief/signal-summary", project)
                .param("filter", "decision")
                .header("Authorization", "Bearer " + accessTokens.issueAccessToken(jinsu.id()))
                .header("API-Version", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(2))
        .andExpect(jsonPath("$.items[0].title").value("최신 결정"))
        .andExpect(jsonPath("$.items[1].title").value("가장 오래된 결정"));
  }

  @Test
  void briefPrefetchesFirstPageAndCursorContinuesToOverflowItem() throws Exception {
    UserProfile jinsu = userService.findOrCreate("brief-it-overflow@momens.works", "신진수", null);
    UUID workspace = insertWorkspace("brief-overflow");
    addMember(workspace, jinsu.id(), "owner");
    UUID project = insertProject(workspace, jinsu.id(), "brief-overflow-project", null, 0, null);
    // 당일 시그널 21개를 생성해 첫 페이지(20개)를 초과하는 상황을 재현합니다. i=0이 가장 오래된 시그널입니다.
    UUID oldest = null;
    for (int i = 0; i < 21; i++) {
      UUID id =
          insertSignal(
              workspace,
              project,
              "decision",
              "시그널 " + i,
              String.format("2026-07-10T00:%02d:00Z", i));
      if (i == 0) {
        oldest = id;
      }
    }

    String briefBody =
        mockMvc
            .perform(
                get("/api/mobile/projects/{projectId}/brief", project)
                    .header("Authorization", "Bearer " + accessTokens.issueAccessToken(jinsu.id()))
                    .header("API-Version", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.signal_summary.items.length()").value(20))
            .andExpect(jsonPath("$.signal_summary.items[0].title").value("시그널 20"))
            .andExpect(jsonPath("$.signal_summary.items[19].title").value("시그널 1"))
            .andExpect(jsonPath("$.signal_summary.next_cursor").isNotEmpty())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String nextCursor = JsonPath.read(briefBody, "$.signal_summary.next_cursor");

    mockMvc
        .perform(
            get("/api/mobile/projects/{projectId}/brief/signal-summary", project)
                .param("cursor", nextCursor)
                .header("Authorization", "Bearer " + accessTokens.issueAccessToken(jinsu.id()))
                .header("API-Version", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].id").value(oldest.toString()))
        .andExpect(jsonPath("$.items[0].title").value("시그널 0"))
        .andExpect(jsonPath("$.next_cursor", nullValue()));
  }

  @Test
  void keepsTodayWindowAcrossMidnightWhenPaginating() throws Exception {
    UserProfile jinsu =
        userService.findOrCreate("brief-it-midnight-jinsu@momens.works", "신진수", null);
    UUID workspace = insertWorkspace("brief-midnight");
    addMember(workspace, jinsu.id(), "owner");
    UUID project = insertProject(workspace, jinsu.id(), "brief-midnight-project", null, 0, null);
    // 모두 KST 기준 2026-07-10 데이터입니다. limit=3으로 최신 3개를 조회하고,
    // 남은 1개는 next_cursor로 이어 조회합니다.
    UUID oldest = insertSignal(workspace, project, "decision", "가장 오래된 결정", "2026-07-10T01:00:00Z");
    insertSignal(workspace, project, "risk", "리스크", "2026-07-10T02:00:00Z");
    insertSignal(workspace, project, "question", "질문", "2026-07-10T03:00:00Z");
    insertSignal(workspace, project, "decision", "최신 결정", "2026-07-10T04:00:00Z");

    String firstPageBody =
        mockMvc
            .perform(
                get("/api/mobile/projects/{projectId}/brief/signal-summary", project)
                    .param("limit", "3")
                    .header("Authorization", "Bearer " + accessTokens.issueAccessToken(jinsu.id()))
                    .header("API-Version", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(3))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String nextCursor = JsonPath.read(firstPageBody, "$.next_cursor");

    // 더보기 요청이 자정을 넘겨 다음 날(KST 2026-07-11)에 도착해도, 커서에 실린 기준일로 같은 창을 복원한다.
    when(mobileClock.clock())
        .thenReturn(Clock.fixed(Instant.parse("2026-07-11T12:00:00Z"), ZoneOffset.UTC));

    mockMvc
        .perform(
            get("/api/mobile/projects/{projectId}/brief/signal-summary", project)
                .param("cursor", nextCursor)
                .param("limit", "3")
                .header("Authorization", "Bearer " + accessTokens.issueAccessToken(jinsu.id()))
                .header("API-Version", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].id").value(oldest.toString()))
        .andExpect(jsonPath("$.next_cursor", nullValue()));
  }

  @Test
  void keepsSingleFilterAcrossCursorPagesAmongOtherTypes() throws Exception {
    UserProfile jinsu =
        userService.findOrCreate("brief-it-filter-page-jinsu@momens.works", "신진수", null);
    UUID workspace = insertWorkspace("brief-filter-page");
    addMember(workspace, jinsu.id(), "owner");
    UUID project = insertProject(workspace, jinsu.id(), "brief-filter-page-project", null, 0, null);
    // 당일(KST 2026-07-10) 범위 안에 decision과 다른 type을 시간순으로 번갈아 심는다.
    UUID d4 = insertSignal(workspace, project, "decision", "결정 4", "2026-07-10T05:00:00Z");
    insertSignal(workspace, project, "risk", "리스크", "2026-07-10T04:30:00Z");
    UUID d3 = insertSignal(workspace, project, "decision", "결정 3", "2026-07-10T04:00:00Z");
    insertSignal(workspace, project, "question", "질문", "2026-07-10T03:30:00Z");
    UUID d2 = insertSignal(workspace, project, "decision", "결정 2", "2026-07-10T03:00:00Z");
    insertSignal(workspace, project, "change", "VOC", "2026-07-10T02:30:00Z");
    UUID d1 = insertSignal(workspace, project, "decision", "결정 1", "2026-07-10T02:00:00Z");

    String firstPageBody =
        mockMvc
            .perform(
                get("/api/mobile/projects/{projectId}/brief/signal-summary", project)
                    .param("filter", "decision")
                    .param("limit", "2")
                    .header("Authorization", "Bearer " + accessTokens.issueAccessToken(jinsu.id()))
                    .header("API-Version", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(2))
            .andExpect(jsonPath("$.items[0].id").value(d4.toString()))
            .andExpect(jsonPath("$.items[0].type").value("decision"))
            .andExpect(jsonPath("$.items[1].id").value(d3.toString()))
            .andExpect(jsonPath("$.items[1].type").value("decision"))
            .andExpect(jsonPath("$.next_cursor").isNotEmpty())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String nextCursor = JsonPath.read(firstPageBody, "$.next_cursor");

    mockMvc
        .perform(
            get("/api/mobile/projects/{projectId}/brief/signal-summary", project)
                .param("filter", "decision")
                .param("limit", "2")
                .param("cursor", nextCursor)
                .header("Authorization", "Bearer " + accessTokens.issueAccessToken(jinsu.id()))
                .header("API-Version", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(2))
        .andExpect(jsonPath("$.items[0].id").value(d2.toString()))
        .andExpect(jsonPath("$.items[0].type").value("decision"))
        .andExpect(jsonPath("$.items[1].id").value(d1.toString()))
        .andExpect(jsonPath("$.items[1].type").value("decision"))
        .andExpect(jsonPath("$.next_cursor", nullValue()));
  }

  @Test
  void returnsValidationFailedForMalformedCursor() throws Exception {
    UserProfile jinsu = userService.findOrCreate("brief-it-cursor-jinsu@momens.works", "신진수", null);
    UUID workspace = insertWorkspace("brief-cursor");
    addMember(workspace, jinsu.id(), "owner");
    UUID project = insertProject(workspace, jinsu.id(), "brief-cursor-project", null, 0, null);

    mockMvc
        .perform(
            get("/api/mobile/projects/{projectId}/brief/signal-summary", project)
                .param("cursor", "not-a-cursor")
                .header("Authorization", "Bearer " + accessTokens.issueAccessToken(jinsu.id()))
                .header("API-Version", "1"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("COMMON_VALIDATION_FAILED"));
  }

  @Test
  void returnsNullableSnapshotFieldsAsNull() throws Exception {
    // target_date와 summary는 스키마상 nullable이라 값이 없으면 null로 항상 포함된다(명세 예시와 동일한 키 구성).
    UserProfile gyuil = userService.findOrCreate("brief-it-gyuil@momens.works", "김규일", null);
    UUID workspace = insertWorkspace("brief-nullable");
    addMember(workspace, gyuil.id(), "owner");
    UUID project = insertProject(workspace, gyuil.id(), "빈 스냅샷 프로젝트", null, 0, null);

    mockMvc
        .perform(
            get("/api/mobile/projects/{projectId}/brief", project)
                .header("Authorization", "Bearer " + accessTokens.issueAccessToken(gyuil.id()))
                .header("API-Version", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.project.name").value("빈 스냅샷 프로젝트"))
        .andExpect(jsonPath("$.project.target_date", nullValue()))
        .andExpect(jsonPath("$.project.progress").value(0))
        .andExpect(jsonPath("$.project.summary", nullValue()));
  }

  @Test
  void progressCountsDoneAmongTasksExceptCancelledAndDeleted() throws Exception {
    UserProfile jinsu =
        userService.findOrCreate("brief-it-progress-jinsu@momens.works", "신진수", null);
    UUID workspace = insertWorkspace("brief-progress");
    addMember(workspace, jinsu.id(), "owner");
    UUID project = insertProject(workspace, jinsu.id(), "brief-progress-project", null, 0, null);
    insertTask(workspace, project, "완료 1", "done", "medium", "2026-07-10T01:00:00Z");
    insertTask(workspace, project, "완료 2", "done", "medium", "2026-07-10T02:00:00Z");
    insertTask(workspace, project, "할 일", "todo", "medium", "2026-07-10T03:00:00Z");
    insertTask(workspace, project, "진행 중", "in_progress", "medium", "2026-07-10T04:00:00Z");
    // 취소한 태스크는 분모에서 빠진다. 넣지 않았다면 2/6이라 33이 됐을 것이다.
    insertTask(workspace, project, "취소 1", "cancelled", "medium", "2026-07-10T05:00:00Z");
    insertTask(workspace, project, "취소 2", "cancelled", "medium", "2026-07-10T06:00:00Z");
    // 소프트 삭제한 완료 태스크도 세지 않는다. 셌다면 3/5이라 60이 됐을 것이다.
    insertDeletedTask(workspace, project, "삭제된 완료", "done", "2026-07-10T07:00:00Z");

    mockMvc
        .perform(
            get("/api/mobile/projects/{projectId}/brief", project)
                .header("Authorization", "Bearer " + accessTokens.issueAccessToken(jinsu.id()))
                .header("API-Version", "1"))
        .andExpect(status().isOk())
        // done 2건 대비 분모 4건(done 2, todo 1, in_progress 1)이라 50이다.
        .andExpect(jsonPath("$.project.progress").value(50));
  }

  @Test
  void progressIsHundredWhenEveryTaskExceptCancelledIsDone() throws Exception {
    UserProfile jinsu =
        userService.findOrCreate("brief-it-cancelled-jinsu@momens.works", "신진수", null);
    UUID workspace = insertWorkspace("brief-cancelled");
    addMember(workspace, jinsu.id(), "owner");
    UUID project = insertProject(workspace, jinsu.id(), "brief-cancelled-project", null, 0, null);
    insertTask(workspace, project, "완료 1", "done", "medium", "2026-07-10T01:00:00Z");
    insertTask(workspace, project, "완료 2", "done", "medium", "2026-07-10T02:00:00Z");
    // cancelled를 분모에 포함하면 진행률이 40%(2/5)에 머물러 100이 될 수 없다.
    insertTask(workspace, project, "취소 1", "cancelled", "medium", "2026-07-10T03:00:00Z");
    insertTask(workspace, project, "취소 2", "cancelled", "medium", "2026-07-10T04:00:00Z");
    insertTask(workspace, project, "취소 3", "cancelled", "medium", "2026-07-10T05:00:00Z");

    assertProgress(project, jinsu.id(), 100);
  }

  @Test
  void progressDecreasesWhenDoneTaskGoesBackToTodo() throws Exception {
    UserProfile jinsu =
        userService.findOrCreate("brief-it-regress-jinsu@momens.works", "신진수", null);
    UUID workspace = insertWorkspace("brief-regress");
    addMember(workspace, jinsu.id(), "owner");
    UUID project = insertProject(workspace, jinsu.id(), "brief-regress-project", null, 0, null);
    UUID done = insertTask(workspace, project, "완료 1", "done", "medium", "2026-07-10T01:00:00Z");
    insertTask(workspace, project, "완료 2", "done", "medium", "2026-07-10T02:00:00Z");
    insertTask(workspace, project, "할 일 1", "todo", "medium", "2026-07-10T03:00:00Z");
    insertTask(workspace, project, "할 일 2", "todo", "medium", "2026-07-10T04:00:00Z");

    assertProgress(project, jinsu.id(), 50);

    // 완료를 되돌리면 진행률도 줄어야 한다. 진행률은 조회 시 태스크 상태를 기준으로 계산하므로 별도 갱신 없이 바로 반영된다.
    jdbcTemplate.update("UPDATE tasks SET status = 'todo' WHERE id = ?", done);

    assertProgress(project, jinsu.id(), 25);
  }

  private void assertProgress(UUID projectId, UUID userId, int expected) throws Exception {
    mockMvc
        .perform(
            get("/api/mobile/projects/{projectId}/brief", projectId)
                .header("Authorization", "Bearer " + accessTokens.issueAccessToken(userId))
                .header("API-Version", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.project.progress").value(expected));
  }

  @Test
  void returnsForbiddenWhenCallerIsNotWorkspaceMember() throws Exception {
    UserProfile gyuil = userService.findOrCreate("brief-it-owner-gyuil@momens.works", "김규일", null);
    UserProfile jinsu =
        userService.findOrCreate("brief-it-stranger-jinsu@momens.works", "신진수", null);
    UUID workspace = insertWorkspace("brief-forbidden");
    addMember(workspace, gyuil.id(), "owner");
    UUID project = insertProject(workspace, gyuil.id(), "brief-forbidden-project", null, 0, null);

    mockMvc
        .perform(
            get("/api/mobile/projects/{projectId}/brief", project)
                .header("Authorization", "Bearer " + accessTokens.issueAccessToken(jinsu.id()))
                .header("API-Version", "1"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("AUTH_FORBIDDEN"));
  }

  @Test
  void returnsNotFoundForUnknownProject() throws Exception {
    UserProfile caller = userService.findOrCreate("brief-it-404@momens.works", "신진수", null);

    mockMvc
        .perform(
            get("/api/mobile/projects/{projectId}/brief", UUID.randomUUID())
                .header("Authorization", "Bearer " + accessTokens.issueAccessToken(caller.id()))
                .header("API-Version", "1"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("PROJECT_NOT_FOUND"));
  }

  @Test
  void returnsNotFoundForUnknownProjectOnSignalSummaryPage() throws Exception {
    UserProfile caller = userService.findOrCreate("brief-it-page-404@momens.works", "신진수", null);

    mockMvc
        .perform(
            get("/api/mobile/projects/{projectId}/brief/signal-summary", UUID.randomUUID())
                .header("Authorization", "Bearer " + accessTokens.issueAccessToken(caller.id()))
                .header("API-Version", "1"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("PROJECT_NOT_FOUND"));
  }

  @Test
  void returnsStandardUnauthorizedWithoutToken() throws Exception {
    mockMvc
        .perform(
            get("/api/mobile/projects/{projectId}/brief", UUID.randomUUID())
                .header("API-Version", "1"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error.code").value("AUTH_UNAUTHORIZED"));
  }

  private UUID insertWorkspace(String slug) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO workspaces (id, name, slug) VALUES (?, ?, ?)", id, "모멘스", slug);
    return id;
  }

  private void addMember(UUID workspaceId, UUID userId, String role) {
    jdbcTemplate.update(
        "INSERT INTO workspace_members (workspace_id, user_id, role) VALUES (?, ?, ?)",
        workspaceId,
        userId,
        role);
  }

  private UUID insertProject(
      UUID workspaceId,
      UUID ownerId,
      String name,
      LocalDate targetDate,
      int progress,
      String summary) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO projects (id, workspace_id, name, owner_id, target_date, progress, summary)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?)",
        id,
        workspaceId,
        name,
        ownerId,
        targetDate,
        progress,
        summary);
    return id;
  }

  private UUID insertSignal(
      UUID workspaceId, UUID projectId, String type, String title, String createdAt) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO signals (id, workspace_id, project_id, type, title, description, created_at)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?)",
        id,
        workspaceId,
        projectId,
        type,
        title,
        "본문",
        Timestamp.from(Instant.parse(createdAt)));
    return id;
  }

  private void insertDeletedSignal(
      UUID workspaceId, UUID projectId, String type, String title, String createdAt) {
    UUID id = insertSignal(workspaceId, projectId, type, title, createdAt);
    jdbcTemplate.update(
        "UPDATE signals SET deleted_at = ? WHERE id = ?",
        Timestamp.from(Instant.parse(createdAt)),
        id);
  }

  private void insertDeletedTask(
      UUID workspaceId, UUID projectId, String title, String status, String createdAt) {
    UUID id = insertTask(workspaceId, projectId, title, status, "medium", createdAt);
    jdbcTemplate.update(
        "UPDATE tasks SET deleted_at = ? WHERE id = ?",
        Timestamp.from(Instant.parse(createdAt)),
        id);
  }

  private UUID insertTask(
      UUID workspaceId,
      UUID projectId,
      String title,
      String status,
      String priority,
      String createdAt) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO tasks (id, workspace_id, project_id, title, status, priority, role,"
            + " created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        id,
        workspaceId,
        projectId,
        title,
        status,
        priority,
        "pm",
        Timestamp.from(Instant.parse(createdAt)));
    return id;
  }

  private void insertAction(UUID workspaceId, UUID signalId, UUID userId) {
    jdbcTemplate.update(
        "INSERT INTO signal_actions (id, workspace_id, signal_id, action_type,"
            + " processed_by_user_id) VALUES (?, ?, ?, ?, ?)",
        UUID.randomUUID(),
        workspaceId,
        signalId,
        "dismiss",
        userId);
  }
}
