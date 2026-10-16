package works.momens.server.mcp.tools.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.InputStream;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import works.momens.server.common.api.BusinessException;
import works.momens.server.common.api.CommonErrorCode;
import works.momens.server.common.api.FieldValidationException;
import works.momens.server.mcp.configuration.McpWebProperties;
import works.momens.server.mcp.grant.McpGrantDetail;
import works.momens.server.mcp.grant.McpGrantReader;
import works.momens.server.mcp.grant.McpScope;
import works.momens.server.mcp.transport.McpAuthenticationContext;
import works.momens.server.mcp.transport.McpToolDefinition;
import works.momens.server.project.core.ProjectDetail;
import works.momens.server.project.core.ProjectDetailReader;
import works.momens.server.project.core.ProjectReader;
import works.momens.server.project.milestone.CreateMilestoneCommand;
import works.momens.server.project.milestone.MilestoneDetail;
import works.momens.server.project.milestone.MilestoneReader;
import works.momens.server.project.milestone.MilestoneWriter;
import works.momens.server.project.milestone.UpdateMilestoneCommand;
import works.momens.server.project.task.CreateTaskCommand;
import works.momens.server.project.task.PatchTaskCommand;
import works.momens.server.project.task.TaskPriority;
import works.momens.server.project.task.TaskReader;
import works.momens.server.project.task.TaskSnapshot;
import works.momens.server.project.task.TaskStatus;
import works.momens.server.project.task.TaskWriter;
import works.momens.server.project.taskupdate.CreateTaskUpdateCommand;
import works.momens.server.project.taskupdate.TaskUpdateKind;
import works.momens.server.project.taskupdate.TaskUpdateReader;
import works.momens.server.project.taskupdate.TaskUpdateWriter;
import works.momens.server.user.UserProfile;
import works.momens.server.user.UserService;
import works.momens.server.workspace.core.WorkspaceDetail;
import works.momens.server.workspace.core.WorkspaceReader;
import works.momens.server.workspace.membership.WorkspaceMembershipDetail;
import works.momens.server.workspace.membership.WorkspaceMembershipReader;
import works.momens.server.workspace.membership.WorkspaceRole;

@DisplayName("MCP 쓰기 도구 서비스 단위 테스트")
class McpWriteToolServiceTest {
  private final WorkspaceReader workspaces = mock(WorkspaceReader.class);
  private final McpTaskLinks links =
      new McpTaskLinks(new McpWebProperties(URI.create("https://app.example.com")), workspaces);
  private final ObjectMapper mapper = new ObjectMapper();
  private final McpGrantReader grants = mock(McpGrantReader.class);
  private final WorkspaceMembershipReader memberships = mock(WorkspaceMembershipReader.class);
  private final UserService users = mock(UserService.class);
  private final ProjectDetailReader projects = mock(ProjectDetailReader.class);
  private final MilestoneReader milestones = mock(MilestoneReader.class);
  private final TaskReader tasks = mock(TaskReader.class);
  private final TaskWriter taskWriter = mock(TaskWriter.class);
  private final MilestoneWriter milestoneWriter = mock(MilestoneWriter.class);
  private final TaskUpdateWriter updates = mock(TaskUpdateWriter.class);
  private final UUID workspaceId = UUID.randomUUID();
  private final UUID userId = UUID.randomUUID();
  private final UUID projectId = UUID.randomUUID();
  private final UUID taskId = UUID.randomUUID();
  private final UUID milestoneId = UUID.randomUUID();
  private final Instant now = Instant.parse("2026-09-28T03:04:00Z");
  private final McpAuthenticationContext context =
      new McpAuthenticationContext(
          UUID.randomUUID(),
          userId,
          "client",
          workspaceId,
          Arrays.stream(McpScope.values()).map(McpScope::value).collect(Collectors.toSet()));
  private McpWriteToolService writes;
  private McpToolService service;

  @BeforeEach
  void setup() throws Exception {
    when(workspaces.findById(workspaceId))
        .thenReturn(
            Optional.of(new WorkspaceDetail(workspaceId, "Momens", "momens", null, now, now)));
    writes =
        new McpWriteToolService(
            mapper,
            grants,
            memberships,
            new McpTaskToolService(
                memberships, users, projects, milestones, tasks, taskWriter, updates, links),
            new McpMilestoneToolService(projects, milestones, milestoneWriter));
    service =
        new McpToolService(
            new McpReadToolService(
                mapper,
                grants,
                memberships,
                users,
                projects,
                mock(ProjectReader.class),
                milestones,
                tasks,
                mock(TaskUpdateReader.class),
                links),
            writes,
            mapper);
    when(grants.findActive(context.grantId())).thenReturn(Optional.of(grant(context.scopes())));
    when(memberships.roleOf(workspaceId, userId)).thenReturn(Optional.of(WorkspaceRole.MEMBER));
  }

  @Test
  @DisplayName("쓰기 도구 schema는 golden과 일치하고 통합 목록은 이름순 정렬과 scope 필터를 적용한다")
  void catalogMatchesGoldenAndCombinedCatalogIsSortedAndScopeFiltered() throws Exception {
    ArrayNode actual = mapper.createArrayNode();
    for (McpToolDefinition tool : writes.list(context)) {
      actual
          .addObject()
          .put("name", tool.name())
          .put("description", tool.description())
          .set("inputSchema", tool.inputSchema());
    }
    try (InputStream input =
        new ClassPathResource("mcp/write-tools-golden.json").getInputStream()) {
      assertThat(actual).isEqualTo(mapper.readTree(input));
    }
    assertThat(service.list(context))
        .extracting(McpToolDefinition::name)
        .containsExactly(
            "create_comment",
            "create_milestone",
            "create_task",
            "delete_milestone",
            "get_task",
            "list_members",
            "list_milestones",
            "list_projects",
            "list_task_updates",
            "list_tasks",
            "list_tasks_v2",
            "update_milestone",
            "update_task");
    assertThat(service.list(context)).isEqualTo(service.list(context));
    ((ObjectNode) writes.list(context).getFirst().inputSchema()).put("type", "array");
    assertThat(writes.list(context).getFirst().inputSchema().path("type").asText())
        .isEqualTo("object");
    assertThat(service.list(withScopes(Set.of(McpScope.TASKS_WRITE.value()))))
        .extracting(McpToolDefinition::name)
        .containsExactly("create_comment", "create_task", "update_task");
    assertThat(service.list(withScopes(Set.of(McpScope.MILESTONES_WRITE.value()))))
        .extracting(McpToolDefinition::name)
        .containsExactly("create_milestone", "delete_milestone", "update_milestone");
    assertThat(service.call("unknown", mapper.createObjectNode(), context)).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "create_task",
        "update_task",
        "create_comment",
        "create_milestone",
        "update_milestone",
        "delete_milestone"
      })
  @DisplayName("철회된 grant는 도메인 접근 전에 모든 쓰기 도구 호출을 차단한다")
  void revokedGrantBlocksAllWritesBeforeDomainAccess(String name) {
    when(grants.findActive(context.grantId())).thenReturn(Optional.empty());
    JsonNode result = call(name, "{}");
    assertThat(result.path("isError").asBoolean()).isTrue();
    assertThat(text(result)).isEqualTo("You don't have access to this tool.");
    verifyNoInteractions(projects, tasks, milestones, users, taskWriter, milestoneWriter, updates);
  }

  @ParameterizedTest
  @ValueSource(strings = {"membership", "scope", "user", "client", "workspace", "token_scope"})
  @DisplayName("쓰기 호출마다 membership·grant 정보와 token scope를 재검증한다")
  void revalidatesEveryAuthorizationDimension(String dimension) {
    McpGrantDetail grant = grant(context.scopes());
    switch (dimension) {
      case "membership" ->
          when(memberships.roleOf(workspaceId, userId)).thenReturn(Optional.empty());
      case "scope" -> grant = grant(Set.of(McpScope.TASKS_READ.value()));
      case "user" ->
          grant =
              new McpGrantDetail(
                  context.grantId(),
                  UUID.randomUUID(),
                  "client",
                  workspaceId,
                  grant.scopes(),
                  now,
                  null,
                  now);
      case "client" ->
          grant =
              new McpGrantDetail(
                  context.grantId(), userId, "other", workspaceId, grant.scopes(), now, null, now);
      case "workspace" ->
          grant =
              new McpGrantDetail(
                  context.grantId(),
                  userId,
                  "client",
                  UUID.randomUUID(),
                  grant.scopes(),
                  now,
                  null,
                  now);
      default -> {}
    }
    when(grants.findActive(context.grantId())).thenReturn(Optional.of(grant));
    McpAuthenticationContext caller =
        dimension.equals("token_scope") ? withScopes(Set.of(McpScope.TASKS_READ.value())) : context;
    JsonNode result = service.call("create_task", mapper.readTree("{}"), caller).orElseThrow();
    assertThat(result.path("isError").asBoolean()).isTrue();
    assertThat(text(result)).isEqualTo("You don't have access to this tool.");
    verifyNoInteractions(projects, tasks, milestones, taskWriter, milestoneWriter, updates);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{}",
        "{\"project\":\"p\",\"title\":\" \"}",
        "{\"project\":\"p\",\"title\":null}",
        "{\"project\":\"p\",\"title\":42}",
        "{\"project\":\"p\",\"title\":\"t\",\"extra\":true}",
        "[]"
      })
  @DisplayName("입력 schema에 맞지 않는 요청은 writer 호출 전에 거부한다")
  void invalidShapeCannotReachWriter(String args) {
    assertThat(call("create_task", args).path("isError").asBoolean()).isTrue();
    verifyNoInteractions(projects, taskWriter);
  }

  @Test
  @DisplayName("쓰기 scope만으로 기존 기본값과 참조 해석을 적용해 태스크를 생성한다")
  void createsTaskWithLegacyDefaultsAndReferencesUsingWriteScopeAlone() {
    seed();
    when(taskWriter.create(any())).thenReturn(task());
    JsonNode result =
        service
            .call(
                "create_task",
                mapper.readTree(
                    """
        {"project":"prj-0003","title":" Write tools ","assignee":"me","milestone":"Release"}
        """),
                withScopes(Set.of(McpScope.TASKS_WRITE.value())))
            .orElseThrow();
    assertThat(text(result))
        .isEqualTo(
            "Created MOM-0993 · Write tools\nproject: PRJ-0003 Sprint · status: todo · priority: medium · milestone: Release · due: 2026-10-01\nurl: https://app.example.com/momens/projects/"
                + projectId
                + "?tab=tasks&task=MOM-0993");
    ArgumentCaptor<CreateTaskCommand> command = ArgumentCaptor.forClass(CreateTaskCommand.class);
    verify(taskWriter).create(command.capture());
    assertThat(command.getValue().workspaceId()).isEqualTo(workspaceId);
    assertThat(command.getValue().projectId()).isEqualTo(projectId);
    assertThat(command.getValue().title()).isEqualTo("Write tools");
    assertThat(command.getValue().status()).isEqualTo(TaskStatus.BACKLOG);
    assertThat(command.getValue().priority()).isEqualTo(TaskPriority.MEDIUM);
    assertThat(command.getValue().assigneeId()).isEqualTo(userId);
    assertThat(command.getValue().milestoneId()).isEqualTo(milestoneId);
  }

  @ParameterizedTest
  @CsvSource({"progress,med", "in-progress,MED", "IN_PROGRESS,medium"})
  @DisplayName("태스크 상태와 우선순위의 기존 별칭을 유지한다")
  void preservesTaskStatusAndPriorityAliases(String status, String priority) {
    seed();
    when(taskWriter.patch(any())).thenReturn(task());
    assertThat(
            call(
                    "update_task",
                    mapper.writeValueAsString(
                        Map.of("task", "mom-0993", "status", status, "priority", priority)))
                .path("isError")
                .asBoolean())
        .isFalse();
    ArgumentCaptor<PatchTaskCommand> command = ArgumentCaptor.forClass(PatchTaskCommand.class);
    verify(taskWriter).patch(command.capture());
    assertThat(command.getValue().status()).isEqualTo(TaskStatus.IN_PROGRESS);
    assertThat(command.getValue().priority()).isEqualTo(TaskPriority.MEDIUM);
  }

  @Test
  @DisplayName("태스크 수정에서 생략한 필드와 명시적으로 삭제한 필드를 구분한다")
  void separatesOmittedFieldsFromExplicitClears() {
    seed();
    when(taskWriter.patch(any())).thenReturn(task());
    call(
        "update_task",
        "{\"task\":\"mom-0993\",\"description\":\"\",\"due_date\":\"\",\"assignee\":\"none\",\"milestone\":\"remove\"}");
    ArgumentCaptor<PatchTaskCommand> command = ArgumentCaptor.forClass(PatchTaskCommand.class);
    verify(taskWriter).patch(command.capture());
    PatchTaskCommand patch = command.getValue();
    assertThat(patch.descriptionSet()).isTrue();
    assertThat(patch.description()).isNull();
    assertThat(patch.dueDateSet()).isTrue();
    assertThat(patch.dueDate()).isNull();
    assertThat(patch.assigneeSet()).isTrue();
    assertThat(patch.assigneeId()).isNull();
    assertThat(patch.milestoneSet()).isTrue();
    assertThat(patch.milestoneId()).isNull();
    assertThat(patch.titleSet()).isFalse();
    assertThat(patch.statusSet()).isFalse();
    assertThat(patch.prioritySet()).isFalse();
  }

  @Test
  @DisplayName("태스크 수정에서 생략한 선택 필드는 삭제하지 않는다")
  void omittedOptionalFieldsAreNotCleared() {
    seed();
    when(taskWriter.patch(any())).thenReturn(task());
    assertThat(text(call("update_task", "{\"task\":\"mom-0993\"}")))
        .endsWith(
            "\nurl: https://app.example.com/momens/projects/"
                + projectId
                + "?tab=tasks&task=MOM-0993");
    ArgumentCaptor<PatchTaskCommand> command = ArgumentCaptor.forClass(PatchTaskCommand.class);
    verify(taskWriter).patch(command.capture());
    assertThat(command.getValue().descriptionSet()).isFalse();
    assertThat(command.getValue().dueDateSet()).isFalse();
    assertThat(command.getValue().assigneeSet()).isFalse();
    assertThat(command.getValue().milestoneSet()).isFalse();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"status\":\"blocked\"}",
        "{\"priority\":\"p1\"}",
        "{\"due_date\":\"2026-02-30\"}",
        "{\"due_date\":\"2026-1-01\"}",
        "{\"description\":null}",
        "{\"milestone\":\"absent\"}",
        "{\"assignee\":\"absent\"}"
      })
  @DisplayName("잘못된 태스크 값이나 참조는 writer 호출 전에 거부한다")
  void invalidTaskValuesCannotReachWriter(String fields) {
    seed();
    ObjectNode args = (ObjectNode) mapper.readTree(fields);
    args.put("task", "MOM-0993");
    assertThat(call("update_task", args.toString()).path("isError").asBoolean()).isTrue();
    verifyNoInteractions(taskWriter);
  }

  @ParameterizedTest
  @ValueSource(strings = {"update_task", "create_comment"})
  @DisplayName("프로젝트가 없거나 삭제되면 UUID와 label을 통한 태스크 쓰기를 차단한다")
  void missingOrDeletedProjectBlocksTaskWritesByUuidAndLabel(String name) {
    seed();
    when(projects.listDetailsByWorkspaceId(workspaceId)).thenReturn(List.of());
    for (String reference : List.of("MOM-0993", taskId.toString(), UUID.randomUUID().toString())) {
      ObjectNode args = mapper.createObjectNode().put("task", reference);
      if (name.equals("create_comment")) {
        args.put("body", "Comment");
      }
      assertThat(call(name, args.toString()).path("isError").asBoolean()).isTrue();
    }
    verifyNoInteractions(taskWriter, updates, workspaces);
  }

  @Test
  @DisplayName("다른 프로젝트의 마일스톤 참조와 중복 이름을 거부한다")
  void rejectsMilestoneFromAnotherProjectAndAmbiguousNames() {
    seed();
    when(milestones.listDetailsByWorkspaceId(workspaceId))
        .thenReturn(List.of(milestone(milestoneId, UUID.randomUUID(), "Release")));
    assertThat(
            call(
                    "create_task",
                    "{\"project\":\"Sprint\",\"title\":\"Task\",\"milestone\":\"Release\"}")
                .path("isError")
                .asBoolean())
        .isTrue();
    when(milestones.listDetailsByWorkspaceId(workspaceId))
        .thenReturn(
            List.of(
                milestone(milestoneId, projectId, "Release"),
                milestone(UUID.randomUUID(), projectId, "Release")));
    assertThat(call("delete_milestone", "{\"milestone\":\"Release\"}").path("isError").asBoolean())
        .isTrue();
    verifyNoInteractions(taskWriter, milestoneWriter);
  }

  @Test
  @DisplayName("태스크 소속을 확인하고 인증된 사용자를 작성자로 댓글을 생성한다")
  void createsCommentUsingAuthenticatedAuthorAndTaskOwnership() {
    seed();
    assertThat(text(call("create_comment", "{\"task\":\"mom-0993\",\"body\":\" Comment \"}")))
        .isEqualTo("Added a comment to MOM-0993.");
    verify(updates)
        .create(
            new CreateTaskUpdateCommand(
                taskId, workspaceId, projectId, userId, "Comment", TaskUpdateKind.COMMENT, null));
  }

  @Test
  @DisplayName("마일스톤 생성·수정·삭제 시 기존 텍스트 응답을 유지한다")
  void createsUpdatesAndDeletesMilestonesWithLegacyText() {
    seed();
    when(milestoneWriter.create(any())).thenReturn(milestone(milestoneId, projectId, "Release"));
    when(milestoneWriter.update(any())).thenReturn(milestone(milestoneId, projectId, "Release"));
    assertThat(
            text(
                call(
                    "create_milestone",
                    "{\"project\":\"Sprint\",\"name\":\" Release \",\"progress\":0}")))
        .isEqualTo(
            "Created milestone Release ("
                + milestoneId
                + ").\nproject: PRJ-0003 Sprint · status: active · target: 2026-10-01");
    ArgumentCaptor<CreateMilestoneCommand> create =
        ArgumentCaptor.forClass(CreateMilestoneCommand.class);
    verify(milestoneWriter).create(create.capture());
    assertThat(create.getValue().requesterId()).isEqualTo(userId);
    assertThat(create.getValue().ownerUserIds()).isEmpty();
    assertThat(create.getValue().progress()).isZero();
    assertThat(text(call("update_milestone", "{\"milestone\":\"Release\",\"progress\":40}")))
        .isEqualTo(
            "Updated milestone Release ("
                + milestoneId
                + ").\nstatus: active · health: on_track · progress: 40% · target: 2026-10-01");
    verify(milestoneWriter)
        .update(new UpdateMilestoneCommand(milestoneId, "", "", "", null, "", 40, ""));
    assertThat(text(call("delete_milestone", "{\"milestone\":\"" + milestoneId + "\"}")))
        .isEqualTo("Removed milestone Release (" + milestoneId + ").");
    verify(milestoneWriter).delete(milestoneId);
  }

  @ParameterizedTest
  @ValueSource(strings = {"-1", "101", "1.5", "2147483648", "null", "\"10\""})
  @DisplayName("범위나 타입이 잘못된 마일스톤 진행률을 거부한다")
  void invalidProgressIsRejected(String value) {
    seed();
    assertThat(
            call("update_milestone", "{\"milestone\":\"Release\",\"progress\":" + value + "}")
                .path("isError")
                .asBoolean())
        .isTrue();
    verifyNoInteractions(milestoneWriter);
  }

  @Test
  @DisplayName("예상한 writer 오류는 상세를 숨겨 변환하고 내부 오류는 전파한다")
  void mapsExpectedWriterErrorsWithoutExposingDetailsAndPropagatesInternalErrors() {
    seed();
    when(taskWriter.patch(any()))
        .thenThrow(FieldValidationException.forField("assignee_id", "private detail"));
    assertThat(text(call("update_task", "{\"task\":\"MOM-0993\"}")))
        .isEqualTo("Invalid value or reference for this tool.");
    doThrow(new BusinessException(CommonErrorCode.COMMON_NOT_FOUND, "private detail"))
        .when(taskWriter)
        .patch(any());
    assertThat(text(call("update_task", "{\"task\":\"MOM-0993\"}")))
        .isEqualTo("Resource not found in this workspace.");
    doThrow(new IllegalStateException("private SQL")).when(taskWriter).patch(any());
    assertThatThrownBy(() -> call("update_task", "{\"task\":\"MOM-0993\"}"))
        .isInstanceOf(IllegalStateException.class);
  }

  private void seed() {
    when(projects.listDetailsByWorkspaceId(workspaceId))
        .thenReturn(
            List.of(
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
                    now)));
    when(tasks.findSnapshotByLabel(workspaceId, "MOM-0993")).thenReturn(Optional.of(task()));
    when(tasks.findSnapshotInWorkspace(workspaceId, taskId)).thenReturn(Optional.of(task()));
    when(milestones.listDetailsByWorkspaceId(workspaceId))
        .thenReturn(List.of(milestone(milestoneId, projectId, "Release")));
    when(memberships.listMembershipDetails(workspaceId))
        .thenReturn(List.of(new WorkspaceMembershipDetail(userId, "member", now, now)));
    when(users.getProfiles(List.of(userId)))
        .thenReturn(
            List.of(new UserProfile(userId, "alice@example.com", "Alice", null, null, now, now)));
  }

  private TaskSnapshot task() {
    return new TaskSnapshot(
        taskId,
        workspaceId,
        projectId,
        milestoneId,
        "MOM-0993",
        "Write tools",
        "Description",
        "todo",
        "medium",
        null,
        userId,
        LocalDate.parse("2026-10-01"),
        now,
        now);
  }

  private MilestoneDetail milestone(UUID id, UUID project, String name) {
    return new MilestoneDetail(
        id,
        project,
        name,
        null,
        LocalDate.parse("2026-10-01"),
        "active",
        List.of(),
        "on_track",
        40,
        null,
        null,
        now,
        now);
  }

  private McpGrantDetail grant(Set<String> scopes) {
    return new McpGrantDetail(
        context.grantId(), userId, "client", workspaceId, List.copyOf(scopes), now, null, now);
  }

  private McpAuthenticationContext withScopes(Set<String> scopes) {
    return new McpAuthenticationContext(context.grantId(), userId, "client", workspaceId, scopes);
  }

  private JsonNode call(String name, String args) {
    return service.call(name, mapper.readTree(args), context).orElseThrow();
  }

  private static String text(JsonNode result) {
    assertThat(result.path("resultType").asText()).isEqualTo("complete");
    return result.path("content").get(0).path("text").asText();
  }
}
