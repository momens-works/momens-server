package works.momens.server.mcp.tools.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import works.momens.server.mcp.grant.McpGrantDetail;
import works.momens.server.mcp.grant.McpGrantReader;
import works.momens.server.mcp.grant.McpScope;
import works.momens.server.mcp.transport.McpAuthenticationContext;
import works.momens.server.mcp.transport.McpToolDefinition;
import works.momens.server.project.core.ProjectDetail;
import works.momens.server.project.core.ProjectDetailReader;
import works.momens.server.project.milestone.MilestoneDetail;
import works.momens.server.project.milestone.MilestoneReader;
import works.momens.server.project.task.TaskReader;
import works.momens.server.project.task.TaskSnapshot;
import works.momens.server.project.task.TaskStatus;
import works.momens.server.project.taskupdate.TaskUpdateDetail;
import works.momens.server.project.taskupdate.TaskUpdateReader;
import works.momens.server.user.UserProfile;
import works.momens.server.user.UserService;
import works.momens.server.workspace.membership.WorkspaceMembershipDetail;
import works.momens.server.workspace.membership.WorkspaceMembershipReader;
import works.momens.server.workspace.membership.WorkspaceRole;

class McpReadToolServiceTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private final McpGrantReader grants = mock(McpGrantReader.class);
  private final WorkspaceMembershipReader memberships = mock(WorkspaceMembershipReader.class);
  private final UserService users = mock(UserService.class);
  private final ProjectDetailReader projects = mock(ProjectDetailReader.class);
  private final MilestoneReader milestones = mock(MilestoneReader.class);
  private final TaskReader tasks = mock(TaskReader.class);
  private final TaskUpdateReader updates = mock(TaskUpdateReader.class);
  private final UUID workspaceId = UUID.randomUUID();
  private final UUID userId = UUID.randomUUID();
  private final UUID projectId = UUID.randomUUID();
  private final UUID taskId = UUID.randomUUID();
  private final UUID milestoneId = UUID.randomUUID();
  private final Instant now = Instant.parse("2026-09-27T03:04:00Z");
  private final McpAuthenticationContext context =
      new McpAuthenticationContext(
          UUID.randomUUID(),
          userId,
          "client",
          workspaceId,
          Arrays.stream(McpScope.values()).map(McpScope::value).collect(Collectors.toSet()));
  private McpReadToolService service;

  @BeforeEach
  void setup() throws Exception {
    service =
        new McpReadToolService(
            mapper, grants, memberships, users, projects, milestones, tasks, updates);
    when(grants.findActive(context.grantId()))
        .thenReturn(Optional.of(grant(context.scopes().stream().toList())));
    when(memberships.roleOf(workspaceId, userId)).thenReturn(Optional.of(WorkspaceRole.MEMBER));
  }

  @Test
  void catalogMatchesGoldenAndDoesNotExposeMutableSchema() throws Exception {
    ArrayNode actual = mapper.createArrayNode();
    for (McpToolDefinition tool : service.list(context)) {
      actual
          .addObject()
          .put("name", tool.name())
          .put("description", tool.description())
          .set("inputSchema", tool.inputSchema());
    }
    try (InputStream input = new ClassPathResource("mcp/read-tools-golden.json").getInputStream()) {
      assertThat(actual).isEqualTo(mapper.readTree(input));
    }
    assertThat(service.list(context)).isEqualTo(service.list(context));
    ((ObjectNode) service.list(context).getFirst().inputSchema()).put("type", "array");
    assertThat(service.list(context).getFirst().inputSchema().path("type").asText())
        .isEqualTo("object");
    McpAuthenticationContext limited =
        new McpAuthenticationContext(
            context.grantId(), userId, "client", workspaceId, Set.of(McpScope.TASKS_READ.value()));
    assertThat(service.list(limited))
        .extracting(McpToolDefinition::name)
        .containsExactly("get_task", "list_tasks", "list_tasks_v2", "list_task_updates");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "list_projects",
        "list_members",
        "list_milestones",
        "list_tasks",
        "list_tasks_v2",
        "get_task",
        "list_task_updates"
      })
  void revokedGrantBlocksEveryToolBeforeDomainReads(String name) {
    when(grants.findActive(context.grantId())).thenReturn(Optional.empty());
    assertThat(call(name, "{}").path("isError").asBoolean()).isTrue();
    verifyNoInteractions(projects, milestones, tasks, updates, users);
  }

  @Test
  void deniesRemovedMembershipAndReducedGrantScopes() {
    when(memberships.roleOf(workspaceId, userId)).thenReturn(Optional.empty());
    assertThat(call("list_projects", "{}").path("isError").asBoolean()).isTrue();
    when(memberships.roleOf(workspaceId, userId)).thenReturn(Optional.of(WorkspaceRole.MEMBER));
    when(grants.findActive(context.grantId()))
        .thenReturn(Optional.of(grant(List.of(McpScope.TASKS_READ.value()))));
    assertThat(call("list_projects", "{}").path("isError").asBoolean()).isTrue();
    verifyNoInteractions(projects, tasks);
  }

  @Test
  void rejectsMismatchedGrantIdentityAndMissingTokenScope() {
    McpAuthenticationContext wrongWorkspace =
        new McpAuthenticationContext(
            context.grantId(), userId, "client", UUID.randomUUID(), context.scopes());
    assertThat(
            service
                .call("list_projects", mapper.createObjectNode(), wrongWorkspace)
                .orElseThrow()
                .path("isError")
                .asBoolean())
        .isTrue();
    McpAuthenticationContext limited =
        new McpAuthenticationContext(
            context.grantId(), userId, "client", workspaceId, Set.of(McpScope.TASKS_READ.value()));
    assertThat(
            service
                .call("list_projects", mapper.createObjectNode(), limited)
                .orElseThrow()
                .path("isError")
                .asBoolean())
        .isTrue();
    verifyNoInteractions(projects);
  }

  @Test
  void preservesProjectMemberAndMilestoneText() {
    seed();
    assertThat(text(call("list_projects", "{}")))
        .isEqualTo("1 project(s):\n- PRJ-0003 Sprint [active]");
    assertThat(text(call("list_members", "{}")))
        .isEqualTo("1 member(s):\n- Alice <alice@example.com> [member]");
    assertThat(text(call("list_milestones", "{\"project\":\"prj-0003\"}")))
        .isEqualTo(
            "1 milestone(s):\n- Release ("
                + milestoneId
                + ") [active] · health: on_track · progress: 40% — PRJ-0003 Sprint · target: 2026-10-01");
  }

  @Test
  void filtersTasksAndDisplaysDetailActivities() {
    seed();
    when(tasks.listPage(workspaceId, projectId, userId, false, "todo", null, null, 21))
        .thenAnswer(inv -> tasks.listPage(workspaceId, null, null, false, null, null, null, 21));
    when(tasks.listPage(workspaceId, null, userId, false, null, null, null, 21))
        .thenAnswer(inv -> tasks.listPage(workspaceId, null, null, false, null, null, null, 21));
    assertThat(
            text(
                call(
                    "list_tasks",
                    "{\"project\":\"sprint\",\"assignee\":\"me\",\"status\":\" TODO \"}")))
        .isEqualTo("1 task(s):\n- MOM-0991 · Read tools [todo] — PRJ-0003 Sprint");
    assertThat(text(call("list_tasks", "{\"assignee\":\"ALICE@example.com\"}")))
        .contains("MOM-0991");
    assertThat(text(call("list_tasks", "{\"status\":\"unknown\"}"))).isEqualTo("No tasks match.");
    assertThat(text(call("get_task", "{\"task\":\" mom-0991 \"}")))
        .isEqualTo(
            "MOM-0991 · Read tools\nstatus: todo · priority: medium · project: PRJ-0003 Sprint · milestone: Release · assignee: Alice · due: 2026-10-01\n\nDescription\n\nTotal activities: 1\n1 activity(s):\n- [2026-09-27 03:04 UTC] comment · Alice: Comment\nTo read activities, call list_task_updates with task: "
                + taskId);
    verify(tasks).findSnapshotByLabel(workspaceId, "MOM-0991");
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {" "})
  void batchesActivityAuthorsAndPreservesMissingProfilesAndKinds(String missingName) {
    seed();
    UUID bobId = UUID.randomUUID();
    UUID missingId = UUID.randomUUID();
    UUID unnamedId = UUID.randomUUID();
    when(updates.listPage(taskId, null, null, 11))
        .thenReturn(
            List.of(
                activity(userId, "comment", "First"),
                activity(bobId, "update", "Progress"),
                activity(userId, "update", "Again"),
                activity(missingId, "comment", "Missing profile"),
                activity(unnamedId, "update", "No name"),
                activity(null, "comment", "Deleted author")));
    when(users.getProfiles(List.of(userId, bobId, missingId, unnamedId)))
        .thenReturn(
            List.of(
                new UserProfile(userId, "alice@example.com", "Alice", null, null, now, now),
                new UserProfile(bobId, "bob@example.com", "Bob", null, null, now, now),
                new UserProfile(
                    unnamedId, "unnamed@example.com", missingName, null, null, now, now)));

    assertThat(text(call("get_task", "{\"task\":\"MOM-0991\"}")))
        .contains(
            "6 activity(s):"
                + "\n- [2026-09-27 03:04 UTC] comment · Alice: First"
                + "\n- [2026-09-27 03:04 UTC] update · Bob: Progress"
                + "\n- [2026-09-27 03:04 UTC] update · Alice: Again"
                + "\n- [2026-09-27 03:04 UTC] comment · "
                + missingId
                + ": Missing profile"
                + "\n- [2026-09-27 03:04 UTC] update · "
                + unnamedId
                + ": No name"
                + "\n- [2026-09-27 03:04 UTC] comment · Unknown author: Deleted author")
        .doesNotContain("@example.com");
    verify(users).getProfiles(List.of(userId));
    verify(users, times(1)).getProfiles(List.of(userId, bobId, missingId, unnamedId));
    verifyNoMoreInteractions(users);
  }

  @Test
  void preservesActivitiesWithoutAuthorIdsAndSkipsAuthorLookup() {
    seed();
    when(updates.listPage(taskId, null, null, 11))
        .thenReturn(
            List.of(activity(null, "comment", "Comment"), activity(null, "update", "Progress")));

    assertThat(text(call("get_task", "{\"task\":\"MOM-0991\"}")))
        .contains(
            "2 activity(s):"
                + "\n- [2026-09-27 03:04 UTC] comment · Unknown author: Comment"
                + "\n- [2026-09-27 03:04 UTC] update · Unknown author: Progress");
    verify(users).getProfiles(List.of(userId));
    verifyNoMoreInteractions(users);
  }

  private TaskUpdateDetail activity(UUID authorId, String kind, String body) {
    return new TaskUpdateDetail(
        UUID.randomUUID(), workspaceId, projectId, taskId, authorId, body, kind, null, now, now);
  }

  @ParameterizedTest
  @ValueSource(strings = {"none", "unassign", " UNASSIGNED "})
  void v2UnassignedAliasesPreserveLegacyContract(String alias) {
    String args = mapper.createObjectNode().put("assignee", alias).toString();
    call("list_tasks_v2", args);
    verify(tasks).listPage(workspaceId, null, null, true, null, null, null, 21);
    call("list_tasks", args);
    verify(tasks).listPage(workspaceId, null, null, false, null, null, null, 21);
  }

  @ParameterizedTest
  @ValueSource(strings = {"me", " ALICE@example.com ", "alice", "Ali"})
  void v2ResolvesMemberReferences(String reference) {
    seed();
    call("list_tasks_v2", mapper.createObjectNode().put("assignee", reference).toString());
    verify(tasks).listPage(workspaceId, null, userId, false, null, null, null, 21);
  }

  @Test
  void resolvesMemberBeyondFirstBatchWithoutCollectingWholeWorkspace() {
    List<WorkspaceMembershipDetail> first =
        IntStream.range(0, 100)
            .mapToObj(i -> new WorkspaceMembershipDetail(UUID.randomUUID(), "member", now, now))
            .toList();
    UUID target = UUID.randomUUID();
    when(memberships.listPage(workspaceId, null, null, 100)).thenReturn(first);
    when(memberships.listPage(workspaceId, now, first.getLast().userId(), 100))
        .thenReturn(List.of(new WorkspaceMembershipDetail(target, "member", now, now)));
    when(users.getProfiles(List.of(target)))
        .thenReturn(
            List.of(new UserProfile(target, "target@example.com", "Target", null, null, now, now)));
    call("list_tasks_v2", "{\"assignee\":\"Target\"}");
    verify(tasks).listPage(workspaceId, null, target, false, null, null, null, 21);
    verify(memberships).listPage(workspaceId, now, first.getLast().userId(), 100);
  }

  @Test
  void pushesCombinedFiltersAndLimitToReader() {
    seed();
    call(
        "list_tasks_v2",
        "{\"project\":\"PRJ-0003\",\"assignee\":\"none\",\"status\":\" TODO \",\"limit\":3}");
    verify(tasks).listPage(workspaceId, projectId, null, true, "todo", null, null, 4);
  }

  @ParameterizedTest
  @ValueSource(strings = {"unknown", "", "  ", "in-progress", "progress"})
  @DisplayName("v2는 잘못된 상태를 수정 가능한 도구 오류로 반환한다")
  void v2RejectsInvalidStatusBeforeDomainReads(String status) {
    JsonNode result =
        call("list_tasks_v2", mapper.createObjectNode().put("status", status).toString());
    assertThat(result.path("isError").asBoolean()).isTrue();
    assertThat(text(result))
        .contains("Invalid status", "backlog, todo, in_progress, done, or cancelled");
    verifyNoInteractions(projects, tasks, users);
  }

  @Test
  @DisplayName("v2 상태 schema는 정규화 입력을 제한하지 않고 서버는 모든 도메인 상태를 허용한다")
  void v2SchemaAllowsNormalizedDomainStatuses() {
    JsonNode schema =
        service.list(context).stream()
            .filter(tool -> tool.name().equals("list_tasks_v2"))
            .findFirst()
            .orElseThrow()
            .inputSchema()
            .path("properties")
            .path("status");
    assertThat(schema.propertyNames()).containsExactlyInAnyOrder("type", "description");
    assertThat(schema.path("type").asText()).isEqualTo("string");
    for (TaskStatus status : TaskStatus.values()) {
      assertThat(schema.path("description").asText()).contains(status.value());
      for (String value :
          List.of(status.value(), " " + status.value().toUpperCase(Locale.ROOT) + " ")) {
        assertThat(
                call("list_tasks_v2", mapper.createObjectNode().put("status", value).toString())
                    .path("isError")
                    .asBoolean())
            .isFalse();
      }
    }
  }

  @Test
  @DisplayName("v2는 다른 워크스페이스와 부족한 scope의 호출을 조회 전에 거부한다")
  void v2RejectsWorkspaceAndScopeMismatch() {
    for (McpAuthenticationContext denied :
        List.of(
            new McpAuthenticationContext(
                context.grantId(), userId, "client", UUID.randomUUID(), context.scopes()),
            new McpAuthenticationContext(
                context.grantId(),
                userId,
                "client",
                workspaceId,
                Set.of(McpScope.PROJECTS_READ.value())))) {
      assertThat(
              service
                  .call("list_tasks_v2", mapper.createObjectNode(), denied)
                  .orElseThrow()
                  .path("isError")
                  .asBoolean())
          .isTrue();
    }
    verifyNoInteractions(projects, tasks, users);
  }

  @Test
  @DisplayName("v2는 탈퇴한 멤버와 읽기 scope가 제거된 grant를 거부한다")
  void v2RejectsRemovedMembershipAndGrantScope() {
    when(memberships.roleOf(workspaceId, userId)).thenReturn(Optional.empty());
    assertThat(call("list_tasks_v2", "{}").path("isError").asBoolean()).isTrue();
    when(memberships.roleOf(workspaceId, userId)).thenReturn(Optional.of(WorkspaceRole.MEMBER));
    when(grants.findActive(context.grantId()))
        .thenReturn(Optional.of(grant(List.of(McpScope.PROJECTS_READ.value()))));
    assertThat(call("list_tasks_v2", "{}").path("isError").asBoolean()).isTrue();
    verifyNoInteractions(projects, tasks, users);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"status\":null}",
        "{\"status\":42}",
        "{\"assignee\":null}",
        "{\"workspace_id\":\"other\"}"
      })
  @DisplayName("v2는 null·잘못된 타입·정의되지 않은 입력을 거부한다")
  void v2RejectsInvalidArgumentTypes(String args) {
    assertThat(call("list_tasks_v2", args).path("isError").asBoolean()).isTrue();
    verifyNoInteractions(projects, tasks, users);
  }

  @Test
  void taskLookupIsScopedBeforeReadingComments() {
    assertThat(call("get_task", "{\"task\":\"" + taskId + "\"}").path("isError").asBoolean())
        .isTrue();
    verify(tasks).findSnapshotInWorkspace(workspaceId, taskId);
    verifyNoInteractions(updates, projects, milestones, users);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void rejectsTaskInDeletedProjectBeforeReadingComments(boolean useId) {
    seed();
    TaskSnapshot task =
        tasks.listPage(workspaceId, null, null, false, null, null, null, 21).getFirst();
    when(tasks.findSnapshotInWorkspace(workspaceId, taskId)).thenReturn(Optional.of(task));
    when(projects.listByIds(workspaceId, List.of(projectId))).thenReturn(List.of());
    String reference = useId ? taskId.toString() : "mom-0991";

    JsonNode result = call("get_task", "{\"task\":\"" + reference + "\"}");

    assertThat(result.path("isError").asBoolean()).isTrue();
    assertThat(text(result)).isEqualTo("No task in this workspace: " + reference);
    verifyNoInteractions(updates, milestones, users);
  }

  @Test
  void distinguishesUnknownToolsFromInvalidArguments() {
    assertThat(service.call("create_task", mapper.createObjectNode(), context)).isEmpty();
    assertThat(call("get_task", "{}").path("isError").asBoolean()).isTrue();
    assertThat(call("list_tasks", "{\"status\":42}").path("isError").asBoolean()).isTrue();
    assertThat(call("list_projects", "{\"workspace_id\":\"other\"}").path("isError").asBoolean())
        .isTrue();
    verifyNoInteractions(projects, tasks);
  }

  @Test
  void emptyListsKeepLegacyMessages() {
    assertThat(text(call("list_projects", "{}"))).isEqualTo("No projects in this workspace yet.");
    assertThat(text(call("list_members", "{}"))).isEqualTo("No members found.");
    assertThat(text(call("list_milestones", "{}"))).isEqualTo("No milestones match.");
    assertThat(text(call("list_tasks", "{}"))).isEqualTo("No tasks match.");
  }

  @Test
  void projectResolutionDoesNotLetLongInputMatchShortNameAndRejectsAmbiguity() {
    seed();
    ProjectDetail first = projects.listByIds(workspaceId, List.of(projectId)).getFirst();
    ProjectDetail duplicate =
        new ProjectDetail(
            UUID.randomUUID(),
            workspaceId,
            "PRJ-0004",
            "Sprint",
            null,
            "active",
            userId,
            List.of(userId),
            null,
            "planned",
            null,
            0,
            0,
            null,
            null,
            now,
            now);
    when(projects.findReferenceCandidates(eq(workspaceId), any(), anyString()))
        .thenReturn(List.of(first, duplicate));
    when(tasks.listPage(workspaceId, projectId, null, false, null, null, null, 21))
        .thenAnswer(inv -> tasks.listPage(workspaceId, null, null, false, null, null, null, 21));
    assertThat(call("list_tasks", "{\"project\":\"Sprint\"}").path("isError").asBoolean()).isTrue();
    assertThat(call("list_tasks", "{\"project\":\"Sprint extended\"}").path("isError").asBoolean())
        .isTrue();
    assertThat(text(call("list_tasks", "{\"project\":\"PRJ-0003\"}"))).contains("MOM-0991");
    assertThat(
            call("list_tasks", "{\"project\":\"" + UUID.randomUUID() + "\"}")
                .path("isError")
                .asBoolean())
        .isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {"Alice", "Ali"})
  void ambiguousAssigneeRequiresEmail(String reference) {
    UUID otherUserId = UUID.randomUUID();
    when(memberships.listPage(workspaceId, null, null, 100))
        .thenReturn(
            List.of(
                new WorkspaceMembershipDetail(userId, "member", now, now),
                new WorkspaceMembershipDetail(otherUserId, "member", now, now)));
    when(users.getProfiles(List.of(userId, otherUserId)))
        .thenReturn(
            List.of(
                new UserProfile(userId, "alice@example.com", "Alice", null, null, now, now),
                new UserProfile(otherUserId, "other@example.com", "Alice", null, null, now, now)));
    JsonNode result = call("list_tasks", "{\"assignee\":\"" + reference + "\"}");
    assertThat(result.path("isError").asBoolean()).isTrue();
    assertThat(text(result)).isEqualTo("This reference matches several members — use their email");
    verifyNoInteractions(tasks);
    assertThat(
            call("list_tasks", "{\"assignee\":\"alice@example.com\"}").path("isError").asBoolean())
        .isFalse();
    verify(tasks).listPage(workspaceId, null, userId, false, null, null, null, 21);
  }

  @Test
  void taskWithoutOptionalFieldsKeepsMinimalText() {
    seed();
    TaskSnapshot task =
        new TaskSnapshot(
            taskId,
            workspaceId,
            projectId,
            null,
            null,
            "Read tools",
            null,
            "todo",
            "medium",
            "implementation",
            null,
            null,
            now,
            now);
    when(tasks.findSnapshotInWorkspace(workspaceId, taskId)).thenReturn(Optional.of(task));
    when(tasks.listPage(workspaceId, null, null, false, null, null, null, 21))
        .thenReturn(List.of(task));
    when(updates.listPage(taskId, null, null, 11)).thenReturn(List.of());
    JsonNode detail = call("get_task", "{\"task\":\"" + taskId + "\"}");
    assertThat(detail.path("isError").asBoolean()).isFalse();
    assertThat(text(detail))
        .isEqualTo(
            taskId
                + " · Read tools\nstatus: todo · priority: medium · project: PRJ-0003 Sprint\n\nTotal activities: 1\nNo activities.\nTo read activities, call list_task_updates with task: "
                + taskId);
    assertThat(text(call("list_tasks", "{}")))
        .isEqualTo("1 task(s):\n- " + taskId + " · Read tools [todo] — PRJ-0003 Sprint");
    verifyNoInteractions(milestones, users);
  }

  private McpGrantDetail grant(List<String> scopes) {
    return new McpGrantDetail(
        context.grantId(), userId, "client", workspaceId, scopes, now, null, now);
  }

  private void seed() {
    ProjectDetail project =
        new ProjectDetail(
            projectId,
            workspaceId,
            "PRJ-0003",
            "Sprint",
            null,
            "active",
            userId,
            List.of(userId),
            null,
            "planned",
            null,
            0,
            0,
            null,
            null,
            now,
            now);
    TaskSnapshot task =
        new TaskSnapshot(
            taskId,
            workspaceId,
            projectId,
            milestoneId,
            "MOM-0991",
            "Read tools",
            "Description",
            "todo",
            "medium",
            "implementation",
            userId,
            LocalDate.parse("2026-10-01"),
            now,
            now);
    when(projects.listByIds(workspaceId, List.of(projectId))).thenReturn(List.of(project));
    when(projects.listPage(workspaceId, null, null, 21)).thenReturn(List.of(project));
    when(projects.findReferenceCandidates(eq(workspaceId), any(), anyString()))
        .thenReturn(List.of(project));
    when(memberships.listPage(workspaceId, null, null, 21))
        .thenAnswer(inv -> memberships.listPage(workspaceId, null, null, 100));
    when(milestones.listPage(eq(workspaceId), any(), isNull(), isNull(), eq(21)))
        .thenAnswer(inv -> milestones.listByIds(workspaceId, List.of(milestoneId)));
    when(updates.countByTaskId(taskId)).thenReturn(1L);
    when(tasks.listPage(workspaceId, null, null, false, null, null, null, 21))
        .thenReturn(List.of(task));
    when(tasks.findSnapshotByLabel(workspaceId, "MOM-0991")).thenReturn(Optional.of(task));
    when(memberships.listPage(workspaceId, null, null, 100))
        .thenReturn(List.of(new WorkspaceMembershipDetail(userId, "member", now, now)));
    when(users.getProfiles(List.of(userId)))
        .thenReturn(
            List.of(new UserProfile(userId, "alice@example.com", "Alice", null, null, now, now)));
    when(milestones.listByIds(workspaceId, List.of(milestoneId)))
        .thenReturn(
            List.of(
                new MilestoneDetail(
                    milestoneId,
                    projectId,
                    "Release",
                    null,
                    LocalDate.parse("2026-10-01"),
                    "active",
                    List.of(),
                    "on_track",
                    40,
                    null,
                    null,
                    now,
                    now)));
    when(updates.listPage(taskId, null, null, 11))
        .thenReturn(
            List.of(
                new TaskUpdateDetail(
                    UUID.randomUUID(),
                    workspaceId,
                    projectId,
                    taskId,
                    userId,
                    "Comment",
                    "comment",
                    null,
                    now,
                    now)));
  }

  private JsonNode call(String name, String args) {
    return service.call(name, mapper.readTree(args), context).orElseThrow();
  }

  private static String text(JsonNode result) {
    assertThat(result.path("resultType").asText()).isEqualTo("complete");
    return result.path("content").get(0).path("text").asText();
  }
}
