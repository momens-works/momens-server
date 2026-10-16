package works.momens.server.web.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.persistence.EntityManagerFactory;
import jakarta.servlet.http.Cookie;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import works.momens.server.auth.AccessTokenTestFactory;
import works.momens.server.common.test.AbstractPostgresIntegrationTest;
import works.momens.server.project.milestone.MilestoneWriter;
import works.momens.server.project.milestone.UpdateMilestoneCommand;
import works.momens.server.user.UserProfile;
import works.momens.server.user.UserService;

/**
 * {@code GET /api/workspaces}, {@code GET /api/workspaces/{workspaceId}} 실배선 통합 테스트(MOM-0851).
 *
 * <p>실토큰(auth public testFixtures)과 실제 PostgreSQL로 보안 체인부터 응답 shape·정렬·에러 매핑까지 끝까지 확인합니다. 사용자는 user
 * public API로 만들고, workspace/멤버십은 아직 생성 public API가 없어 소유 스키마에 SQL로 시드합니다. 레거시 {@code
 * session_token} 쿠키만 가진 요청도 인증을 통과하는지 확인합니다(ADR-0017).
 */
@SpringBootTest
@AutoConfigureMockMvc
class WebWorkspacesIntegrationTest extends AbstractPostgresIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private AccessTokenTestFactory accessTokens;
  @Autowired private UserService userService;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private EntityManagerFactory entityManagerFactory;
  @Autowired private WorkspaceSnapshotService workspaceSnapshotService;
  @Autowired private MilestoneWriter milestoneWriter;

  @Test
  @DisplayName("멤버인 워크스페이스만 생성 시각 내림차순으로 응답한다")
  void returnsMemberWorkspacesSortedByCreatedAtDesc() throws Exception {
    UserProfile caller = userService.findOrCreate("web-it-sorted@momens.works", "홍길동", null);
    UUID older = insertWorkspace("web-it-older", null);
    UUID newer = insertWorkspace("web-it-newer", "제품팀 워크스페이스");
    UUID othersOnly = insertWorkspace("web-it-others-only", null);
    Instant now = Instant.now();
    updateCreatedAt(older, now.minusSeconds(60));
    updateCreatedAt(newer, now);
    addMember(older, caller.id(), "owner");
    addMember(newer, caller.id(), "member");
    addMember(
        othersOnly,
        userService.findOrCreate("web-it-other@momens.works", "박외부", null).id(),
        "owner");

    mockMvc
        .perform(authorized(get("/api/workspaces"), caller.id()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.workspaces.length()").value(2))
        .andExpect(jsonPath("$.workspaces[0].id").value(newer.toString()))
        .andExpect(jsonPath("$.workspaces[0].description").value("제품팀 워크스페이스"))
        .andExpect(jsonPath("$.workspaces[1].id").value(older.toString()))
        .andExpect(jsonPath("$.workspaces[1].description").doesNotExist());
  }

  @Test
  @DisplayName("워크스페이스가 없으면 빈 배열로 응답한다")
  void returnsEmptyArrayWhenCallerHasNoWorkspace() throws Exception {
    UserProfile caller = userService.findOrCreate("web-it-empty@momens.works", "홍길동", null);

    mockMvc
        .perform(authorized(get("/api/workspaces"), caller.id()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.workspaces").isArray())
        .andExpect(jsonPath("$.workspaces.length()").value(0));
  }

  @Test
  @DisplayName("단건 조회는 래퍼 없이 워크스페이스 객체를 응답한다")
  void getReturnsWorkspaceWithoutWrapper() throws Exception {
    UserProfile caller = userService.findOrCreate("web-it-get@momens.works", "홍길동", null);
    UUID workspaceId = insertWorkspace("web-it-get", null);
    addMember(workspaceId, caller.id(), "owner");

    mockMvc
        .perform(authorized(get("/api/workspaces/{workspaceId}", workspaceId), caller.id()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(workspaceId.toString()))
        .andExpect(jsonPath("$.slug").value("web-it-get"))
        .andExpect(jsonPath("$.workspaces").doesNotExist());
  }

  @Test
  @DisplayName("snapshot은 9개 구획과 빈 컬렉션을 레거시 shape로 응답한다")
  void snapshotReturnsAllSectionsWithEmptyCollections() throws Exception {
    UserProfile caller = userService.findOrCreate("web-it-snapshot@momens.works", "홍길동", null);
    UUID workspaceId = insertWorkspace("web-it-snapshot", null);
    addMember(workspaceId, caller.id(), "owner");

    mockMvc
        .perform(
            authorized(get("/api/workspaces/{workspaceId}/snapshot", workspaceId), caller.id()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.workspace.id").value(workspaceId.toString()))
        .andExpect(jsonPath("$.members").isArray())
        .andExpect(jsonPath("$.members.length()").value(1))
        .andExpect(jsonPath("$.projects").isArray())
        .andExpect(jsonPath("$.projects.length()").value(0))
        .andExpect(jsonPath("$.milestones").isArray())
        .andExpect(jsonPath("$.milestones.length()").value(0))
        .andExpect(jsonPath("$.tasks").isArray())
        .andExpect(jsonPath("$.tasks.length()").value(0))
        .andExpect(jsonPath("$.blockers").isArray())
        .andExpect(jsonPath("$.blockers.length()").value(0))
        .andExpect(jsonPath("$.memory_candidates").isArray())
        .andExpect(jsonPath("$.memory_candidates.length()").value(0))
        .andExpect(jsonPath("$.memories").isArray())
        .andExpect(jsonPath("$.memories.length()").value(0))
        .andExpect(jsonPath("$.task_contexts").isArray())
        .andExpect(jsonPath("$.task_contexts.length()").value(0));
  }

  @Test
  @DisplayName("snapshot은 없는 workspace를 404, 비멤버를 403으로 구분한다")
  void snapshotMapsNotFoundAndForbidden() throws Exception {
    UserProfile owner = userService.findOrCreate("web-it-snapshot-owner@momens.works", "홍길동", null);
    UserProfile stranger =
        userService.findOrCreate("web-it-snapshot-stranger@momens.works", "김철수", null);
    UUID workspaceId = insertWorkspace("web-it-snapshot-forbidden", null);
    addMember(workspaceId, owner.id(), "owner");

    mockMvc
        .perform(
            authorized(
                get("/api/workspaces/{workspaceId}/snapshot", UUID.randomUUID()), owner.id()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("WORKSPACE_NOT_FOUND"));
    mockMvc
        .perform(
            authorized(get("/api/workspaces/{workspaceId}/snapshot", workspaceId), stranger.id()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("AUTH_FORBIDDEN"));
  }

  @Test
  @DisplayName("snapshot members는 가입 시각과 사용자 ID 오름차순으로 정렬한다")
  void snapshotSortsMembersByCreatedAtThenUserId() throws Exception {
    UserProfile caller =
        userService.findOrCreate("web-it-snapshot-sort-owner@momens.works", "소유자", null);
    UserProfile first =
        userService.findOrCreate("web-it-snapshot-sort-first@momens.works", "첫째", null);
    UserProfile second =
        userService.findOrCreate("web-it-snapshot-sort-second@momens.works", "둘째", null);
    UUID workspaceId = insertWorkspace("web-it-snapshot-member-order", null);
    addMember(workspaceId, caller.id(), "owner");
    addMember(workspaceId, first.id(), "member");
    addMember(workspaceId, second.id(), "member");
    Instant joinedAt = Instant.parse("2026-08-20T00:00:00Z");
    updateMemberCreatedAt(workspaceId, caller.id(), joinedAt.plusSeconds(1));
    updateMemberCreatedAt(workspaceId, first.id(), joinedAt);
    updateMemberCreatedAt(workspaceId, second.id(), joinedAt);
    // 기대 순서는 사용자 식별자의 16진수 문자열을 기준으로 정합니다.
    // PostgreSQL은 UUID를 부호 없는 16바이트 값으로 비교하지만 Java의 UUID 비교는 상위 64비트를 부호 있는 값으로 다루므로,
    // 두 식별자의 부호 비트가 다르면 서로 반대 순서로 판정합니다.
    boolean firstComesFirst = first.id().toString().compareTo(second.id().toString()) < 0;
    UUID firstAtSameTime = firstComesFirst ? first.id() : second.id();
    UUID secondAtSameTime = firstComesFirst ? second.id() : first.id();

    mockMvc
        .perform(
            authorized(get("/api/workspaces/{workspaceId}/snapshot", workspaceId), caller.id()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.members[0].id").value(firstAtSameTime.toString()))
        .andExpect(jsonPath("$.members[1].id").value(secondAtSameTime.toString()))
        .andExpect(jsonPath("$.members[2].id").value(caller.id().toString()));
  }

  @Test
  @DisplayName("snapshot은 edge 없는 task와 soft-delete된 task를 context에서 제외하고 빈 번들은 유지한다")
  void snapshotFiltersDeletedContextTargetsAndTasks() throws Exception {
    UserProfile caller =
        userService.findOrCreate("web-it-snapshot-soft-delete@momens.works", "홍길동", null);
    UUID workspaceId = insertWorkspace("web-it-snapshot-soft-delete", null);
    addMember(workspaceId, caller.id(), "owner");
    UUID projectId = insertProject(workspaceId, caller.id());
    UUID noEdgeTaskId = insertTask(workspaceId, projectId, "MOM-862-1");
    UUID emptyBundleTaskId = insertTask(workspaceId, projectId, "MOM-862-2");
    UUID deletedTaskId = insertTask(workspaceId, projectId, "MOM-862-3");
    UUID memoryId = UUID.randomUUID();
    UUID sourceRefId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO confirmed_memories (id, workspace_id, memory_type, title, deleted_at) VALUES (?, ?, 'DECISION', '삭제된 메모리', CURRENT_TIMESTAMP)",
        memoryId,
        workspaceId);
    jdbcTemplate.update(
        "INSERT INTO source_refs (id, workspace_id, source_type, source_object_type, source_object_id, title, deleted_at) VALUES (?, ?, 'NOTION', 'PAGE', 'deleted-page', '삭제된 근거', CURRENT_TIMESTAMP)",
        sourceRefId,
        workspaceId);
    link(workspaceId, emptyBundleTaskId, "MEMORY", memoryId);
    link(workspaceId, emptyBundleTaskId, "SOURCE_OBJECT", sourceRefId);
    link(workspaceId, deletedTaskId, "MEMORY", memoryId);
    jdbcTemplate.update(
        "UPDATE tasks SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", deletedTaskId);

    mockMvc
        .perform(
            authorized(get("/api/workspaces/{workspaceId}/snapshot", workspaceId), caller.id()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tasks.length()").value(2))
        .andExpect(jsonPath("$.task_contexts.length()").value(1))
        .andExpect(jsonPath("$.task_contexts[0].task_id").value(emptyBundleTaskId.toString()))
        .andExpect(jsonPath("$.task_contexts[0].memories").isEmpty())
        .andExpect(jsonPath("$.task_contexts[0].source_refs").isEmpty());
  }

  @Test
  @DisplayName("snapshot은 최악 조건에서도 쿼리 예산 14회를 넘지 않는다")
  void snapshotStaysWithinQueryBudget() {
    UserProfile caller =
        userService.findOrCreate("web-it-snapshot-query-budget@momens.works", "홍길동", null);
    UUID workspaceId = insertWorkspace("web-it-snapshot-query-budget", null);
    addMember(workspaceId, caller.id(), "owner");
    UUID projectId = insertProject(workspaceId, caller.id());
    UUID milestoneId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO milestones (id, project_id, name, progress) VALUES (?, ?, '마일스톤', 45)",
        milestoneId,
        projectId);
    UUID taskId = insertTask(workspaceId, projectId, "MOM-862-query-budget");
    UUID memoryId = UUID.randomUUID();
    UUID sourceRefId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO confirmed_memories (id, workspace_id, memory_type, title) VALUES (?, ?, 'DECISION', '결정')",
        memoryId,
        workspaceId);
    jdbcTemplate.update(
        "INSERT INTO source_refs (id, workspace_id, source_type, source_object_type, source_object_id, title) VALUES (?, ?, 'NOTION', 'PAGE', 'query-budget-page', '근거')",
        sourceRefId,
        workspaceId);
    link(workspaceId, taskId, "MEMORY", memoryId);
    link(workspaceId, taskId, "SOURCE_OBJECT", sourceRefId);

    Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    statistics.setStatisticsEnabled(true);
    statistics.clear();

    workspaceSnapshotService.get(workspaceId, caller.id());

    assertThat(statistics.getPrepareStatementCount()).isLessThanOrEqualTo(14);
  }

  @Test
  @DisplayName("snapshot은 연결된 context를 합성하고 project와 milestone의 빈 owner 규칙을 구분한다")
  void snapshotComposesLinkedContextAndOwnerSerializationRules() throws Exception {
    UserProfile caller = userService.findOrCreate("web-it-snapshot-rich@momens.works", "홍길동", null);
    UUID workspaceId = insertWorkspace("web-it-snapshot-rich", null);
    addMember(workspaceId, caller.id(), "owner");
    UUID projectId = insertProject(workspaceId, caller.id());
    UUID milestoneId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO milestones (id, project_id, name, progress) VALUES (?, ?, '마일스톤', 45)",
        milestoneId,
        projectId);
    UUID taskId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO tasks (id, workspace_id, project_id, label, title, status, priority, origin_type) VALUES (?, ?, ?, 'MOM-862', 'snapshot 태스크', 'todo', 'medium', 'manual')",
        taskId,
        workspaceId,
        projectId);
    UUID memoryId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO confirmed_memories (id, workspace_id, memory_type, title) VALUES (?, ?, 'DECISION', '결정')",
        memoryId,
        workspaceId);
    UUID sourceRefId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO source_refs (id, workspace_id, source_type, source_object_type, source_object_id, title) VALUES (?, ?, 'NOTION', 'PAGE', 'page-862', '근거')",
        sourceRefId,
        workspaceId);
    link(workspaceId, taskId, "MEMORY", memoryId);
    link(workspaceId, taskId, "SOURCE_OBJECT", sourceRefId);

    mockMvc
        .perform(
            authorized(get("/api/workspaces/{workspaceId}/snapshot", workspaceId), caller.id()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.projects[0].id").value(projectId.toString()))
        .andExpect(jsonPath("$.projects[0].owner_user_ids[0]").value(caller.id().toString()))
        .andExpect(jsonPath("$.projects[0].progress").doesNotExist())
        .andExpect(jsonPath("$.milestones[0].id").value(milestoneId.toString()))
        .andExpect(jsonPath("$.milestones[0].progress").value(45))
        .andExpect(jsonPath("$.milestones[0].owner_user_ids").doesNotExist())
        .andExpect(jsonPath("$.tasks[0].id").value(taskId.toString()))
        .andExpect(jsonPath("$.task_contexts.length()").value(1))
        .andExpect(jsonPath("$.task_contexts[0].task_id").value(taskId.toString()))
        .andExpect(jsonPath("$.task_contexts[0].memories[0].id").value(memoryId.toString()))
        .andExpect(jsonPath("$.task_contexts[0].source_refs[0].id").value(sourceRefId.toString()));
  }

  @Test
  void snapshotReflectsClearedMilestoneFields() throws Exception {
    UserProfile caller =
        userService.findOrCreate("web-it-milestone-clear@momens.works", "홍길동", null);
    UUID workspaceId = insertWorkspace("web-it-milestone-clear", null);
    addMember(workspaceId, caller.id(), "owner");
    UUID projectId = insertProject(workspaceId, caller.id());
    UUID milestoneId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO milestones (id, project_id, name, description, summary, target_date, progress) "
            + "VALUES (?, ?, '마일스톤', '설명', '요약', '2026-10-01', 45)",
        milestoneId,
        projectId);
    milestoneWriter.update(
        new UpdateMilestoneCommand(
            milestoneId, null, null, true, null, null, true, null, 0, null, true, null));

    mockMvc
        .perform(
            authorized(get("/api/workspaces/{workspaceId}/snapshot", workspaceId), caller.id()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.milestones[0].id").value(milestoneId.toString()))
        .andExpect(jsonPath("$.milestones[0].name").value("마일스톤"))
        .andExpect(jsonPath("$.milestones[0].description").doesNotExist())
        .andExpect(jsonPath("$.milestones[0].summary").doesNotExist())
        .andExpect(jsonPath("$.milestones[0].target_date").doesNotExist())
        .andExpect(jsonPath("$.milestones[0].progress").value(0));
  }

  @Test
  @DisplayName("없는 워크스페이스는 404 WORKSPACE_NOT_FOUND로 응답한다")
  void getReturnsNotFoundForUnknownWorkspace() throws Exception {
    UserProfile caller = userService.findOrCreate("web-it-404@momens.works", "홍길동", null);

    mockMvc
        .perform(authorized(get("/api/workspaces/{workspaceId}", UUID.randomUUID()), caller.id()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("WORKSPACE_NOT_FOUND"));
  }

  @Test
  @DisplayName("path id가 UUID가 아니면 400 COMMON_BAD_REQUEST로 응답한다")
  void getReturnsBadRequestForNonUuidPathId() throws Exception {
    UserProfile caller = userService.findOrCreate("web-it-400@momens.works", "홍길동", null);

    mockMvc
        .perform(authorized(get("/api/workspaces/{workspaceId}", "not-a-uuid"), caller.id()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("COMMON_BAD_REQUEST"));
  }

  @Test
  @DisplayName("멤버가 아니면 403 AUTH_FORBIDDEN으로 응답한다")
  void getReturnsForbiddenWhenCallerIsNotMember() throws Exception {
    UserProfile owner = userService.findOrCreate("web-it-owner@momens.works", "홍길동", null);
    UserProfile stranger = userService.findOrCreate("web-it-stranger@momens.works", "김철수", null);
    UUID workspaceId = insertWorkspace("web-it-forbidden", null);
    addMember(workspaceId, owner.id(), "owner");

    mockMvc
        .perform(authorized(get("/api/workspaces/{workspaceId}", workspaceId), stranger.id()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("AUTH_FORBIDDEN"));
  }

  @Test
  @DisplayName("토큰이 없으면 401 AUTH_UNAUTHORIZED로 응답한다")
  void returnsUnauthorizedWithoutAnyToken() throws Exception {
    mockMvc
        .perform(get("/api/workspaces").header("API-Version", "1"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error.code").value("AUTH_UNAUTHORIZED"));
  }

  @Test
  @DisplayName("access_token 쿠키만으로 인증을 통과한다")
  void authenticatesViaAccessTokenCookie() throws Exception {
    UserProfile caller = userService.findOrCreate("web-it-access-cookie@momens.works", "홍길동", null);

    mockMvc
        .perform(
            get("/api/workspaces")
                .cookie(new Cookie("access_token", accessTokens.issueAccessToken(caller.id())))
                .header("API-Version", "1"))
        .andExpect(status().isOk());
  }

  @Test
  @DisplayName("레거시 session_token 쿠키만으로 인증을 통과한다")
  void authenticatesViaLegacySessionTokenCookieWhenNoHeaderOrAccessCookie() throws Exception {
    UserProfile caller =
        userService.findOrCreate("web-it-session-cookie@momens.works", "홍길동", null);

    mockMvc
        .perform(
            get("/api/workspaces")
                .cookie(new Cookie("session_token", accessTokens.issueAccessToken(caller.id())))
                .header("API-Version", "1"))
        .andExpect(status().isOk());
  }

  @Test
  @DisplayName("이미 사용 중인 slug는 사유와 대체 slug를 함께 응답한다")
  void slugAvailableReportsTakenSlugWithSuggestion() throws Exception {
    UserProfile caller = userService.findOrCreate("web-it-slug-taken@momens.works", "홍길동", null);
    insertWorkspace("web-it-taken", null);

    mockMvc
        .perform(
            authorized(get("/api/workspaces/slug-available"), caller.id())
                .param("slug", "web-it-taken"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.slug").value("web-it-taken"))
        .andExpect(jsonPath("$.available").value(false))
        .andExpect(jsonPath("$.reason").value("taken"))
        .andExpect(jsonPath("$.suggestion").value("web-it-taken-2"));
  }

  @Test
  @DisplayName("사용할 수 있는 slug는 사유 없이 available로 응답한다")
  void slugAvailableReportsFreeSlugWithoutReason() throws Exception {
    UserProfile caller = userService.findOrCreate("web-it-slug-free@momens.works", "홍길동", null);

    mockMvc
        .perform(
            authorized(get("/api/workspaces/slug-available"), caller.id())
                .param("slug", "web-it-free"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.available").value(true))
        .andExpect(jsonPath("$.reason").doesNotExist())
        .andExpect(jsonPath("$.suggestion").doesNotExist());
  }

  @Test
  @DisplayName("예약어로 지정된 slug는 reserved 사유로 응답한다")
  void slugAvailableRejectsReservedSlug() throws Exception {
    UserProfile caller = userService.findOrCreate("web-it-slug-reserved@momens.works", "홍길동", null);

    mockMvc
        .perform(
            authorized(get("/api/workspaces/slug-available"), caller.id())
                .param("slug", "settings"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.available").value(false))
        .andExpect(jsonPath("$.reason").value("reserved"));
  }

  @Test
  @DisplayName("admin은 이름과 slug를 수정할 수 있다")
  void updateAppliesNameAndSlugForAdmin() throws Exception {
    UserProfile caller = userService.findOrCreate("web-it-update-admin@momens.works", "홍길동", null);
    UUID workspaceId = insertWorkspace("web-it-update", "기존 설명");
    addMember(workspaceId, caller.id(), "admin");

    mockMvc
        .perform(
            authorized(patch("/api/workspaces/{workspaceId}", workspaceId), caller.id())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"새 이름\",\"slug\":\"web-it-updated\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("새 이름"))
        .andExpect(jsonPath("$.slug").value("web-it-updated"))
        .andExpect(jsonPath("$.description").value("기존 설명"));
  }

  @Test
  @DisplayName("admin 미만인 멤버의 수정 요청은 403 AUTH_FORBIDDEN으로 응답한다")
  void updateRejectsMemberWithoutAdminRole() throws Exception {
    UserProfile caller = userService.findOrCreate("web-it-update-member@momens.works", "홍길동", null);
    UUID workspaceId = insertWorkspace("web-it-update-member", null);
    addMember(workspaceId, caller.id(), "member");

    mockMvc
        .perform(
            authorized(patch("/api/workspaces/{workspaceId}", workspaceId), caller.id())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"새 이름\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("AUTH_FORBIDDEN"));
  }

  @Test
  @DisplayName("존재하지 않는 워크스페이스의 수정 요청은 404 WORKSPACE_NOT_FOUND로 응답한다")
  void updateReturnsNotFoundForUnknownWorkspace() throws Exception {
    UserProfile caller = userService.findOrCreate("web-it-update-404@momens.works", "홍길동", null);

    mockMvc
        .perform(
            authorized(patch("/api/workspaces/{workspaceId}", UUID.randomUUID()), caller.id())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"새 이름\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("WORKSPACE_NOT_FOUND"));
  }

  @Test
  @DisplayName("다른 워크스페이스에서 사용 중인 slug로 수정하면 409 WORKSPACE_SLUG_ALREADY_EXISTS로 응답한다")
  void updateRejectsSlugTakenByAnotherWorkspace() throws Exception {
    UserProfile caller = userService.findOrCreate("web-it-update-dup@momens.works", "홍길동", null);
    UUID workspaceId = insertWorkspace("web-it-update-mine", null);
    insertWorkspace("web-it-update-theirs", null);
    addMember(workspaceId, caller.id(), "owner");

    mockMvc
        .perform(
            authorized(patch("/api/workspaces/{workspaceId}", workspaceId), caller.id())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"slug\":\"web-it-update-theirs\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("WORKSPACE_SLUG_ALREADY_EXISTS"));
  }

  @Test
  @DisplayName("예약어로 지정된 slug로 수정하면 400 WORKSPACE_RESERVED_SLUG로 응답한다")
  void updateRejectsReservedSlug() throws Exception {
    UserProfile caller =
        userService.findOrCreate("web-it-update-reserved@momens.works", "홍길동", null);
    UUID workspaceId = insertWorkspace("web-it-update-reserved", null);
    addMember(workspaceId, caller.id(), "owner");

    mockMvc
        .perform(
            authorized(patch("/api/workspaces/{workspaceId}", workspaceId), caller.id())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"slug\":\"settings\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("WORKSPACE_RESERVED_SLUG"));
  }

  private MockHttpServletRequestBuilder authorized(
      MockHttpServletRequestBuilder builder, UUID userId) {
    return builder
        .header("Authorization", "Bearer " + accessTokens.issueAccessToken(userId))
        .header("API-Version", "1");
  }

  private UUID insertWorkspace(String slug, String description) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO workspaces (id, name, slug, description) VALUES (?, ?, ?, ?)",
        id,
        "모멘스",
        slug,
        description);
    return id;
  }

  private UUID insertProject(UUID workspaceId, UUID ownerId) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO projects (id, workspace_id, name, owner_id) VALUES (?, ?, '프로젝트', ?)",
        id,
        workspaceId,
        ownerId);
    return id;
  }

  private void link(UUID workspaceId, UUID taskId, String toEntityType, UUID toEntityId) {
    jdbcTemplate.update(
        "INSERT INTO entity_relations (id, workspace_id, from_entity_type, from_entity_id, relation_type, to_entity_type, to_entity_id) VALUES (?, ?, 'TASK', ?, 'LINKED_TO', ?, ?)",
        UUID.randomUUID(),
        workspaceId,
        taskId,
        toEntityType,
        toEntityId);
  }

  private void updateCreatedAt(UUID workspaceId, Instant createdAt) {
    jdbcTemplate.update(
        "UPDATE workspaces SET created_at = ? WHERE id = ?",
        Timestamp.from(createdAt),
        workspaceId);
  }

  private UUID insertTask(UUID workspaceId, UUID projectId, String label) {
    UUID taskId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO tasks (id, workspace_id, project_id, label, title, status, priority, origin_type) VALUES (?, ?, ?, ?, '태스크', 'todo', 'medium', 'manual')",
        taskId,
        workspaceId,
        projectId,
        label);
    return taskId;
  }

  private void updateMemberCreatedAt(UUID workspaceId, UUID userId, Instant createdAt) {
    jdbcTemplate.update(
        "UPDATE workspace_members SET created_at = ? WHERE workspace_id = ? AND user_id = ?",
        Timestamp.from(createdAt),
        workspaceId,
        userId);
  }

  private void addMember(UUID workspaceId, UUID userId, String role) {
    jdbcTemplate.update(
        "INSERT INTO workspace_members (workspace_id, user_id, role) VALUES (?, ?, ?)",
        workspaceId,
        userId,
        role);
  }
}
