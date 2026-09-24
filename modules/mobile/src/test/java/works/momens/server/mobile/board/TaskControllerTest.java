package works.momens.server.mobile.board;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.config.annotation.ApiVersionConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import works.momens.server.minsu.DraftStatus;
import works.momens.server.project.task.TaskDetail;

/**
 * 태스크 상세 컨트롤러가 경로 변수와 Principal을 조합 서비스에 전달하고 명세의 snake_case 응답 형식(checklist 카운트 파생 포함)을 내는지 검증합니다.
 * versioning은 모듈 경계상 슬라이스 안에서 동일하게 구성합니다.
 */
@WebMvcTest(TaskController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(TaskControllerTest.ApiVersioningTestConfig.class)
class TaskControllerTest {

  @Autowired private MockMvc mockMvc;
  @MockitoBean private ProjectTaskService projectTaskService;

  private static final UUID USER_ID = UUID.fromString("5d2f7f3a-5db1-4f2c-8b9e-13607dd1f5e8");
  private static final UUID TASK_ID = UUID.fromString("27afd507-9c7f-4f0d-a2be-fcdab2477b19");
  private static final UUID PROJECT_ID = UUID.fromString("30d9e9fe-f43b-4097-a88e-dc19f0a5b025");
  private static final UUID MATERIAL_ID = UUID.fromString("9f1c2b3a-4d5e-4f60-8a71-2b3c4d5e6f70");
  private static final Instant OCCURRED_AT = Instant.parse("2026-06-28T00:48:00Z");
  private static final UUID QUESTION_ID = UUID.fromString("3f2a1b4c-5d6e-4f70-8a91-b2c3d4e5f6a7");
  private final Principal principal = USER_ID::toString;

  @Test
  void getTaskDetailReturnsSnakeCaseDetailWithDerivedChecklistCounts() throws Exception {
    UUID assigneeId = UUID.randomUUID();
    UUID doneItemId = UUID.randomUUID();
    UUID openItemId = UUID.randomUUID();
    when(projectTaskService.getTaskDetail(TASK_ID, USER_ID))
        .thenReturn(
            new MobileTaskDetailView(
                DraftStatus.GENERATING,
                new MobileTaskDetail(
                    TASK_ID,
                    PROJECT_ID,
                    "1차 와이어프레임",
                    "todo",
                    "frontend",
                    new MobileTaskDetail.Assignee(
                        assigneeId, "김규일", "https://lh3.googleusercontent.com/a/gyuil"),
                    "medium",
                    "이번 범위의 화면 흐름을 정리한다",
                    List.of(
                        new TaskDetail.ChecklistItem(doneItemId, "완료된 기준", true),
                        new TaskDetail.ChecklistItem(openItemId, "남은 기준", false)),
                    List.of(
                        new MobileTaskDetail.Material(
                            MATERIAL_ID,
                            "권한 요청 화면 v2",
                            "설명 문구 변경",
                            "figma",
                            OCCURRED_AT,
                            "https://figma.example/p")),
                    List.of(new TaskDetail.OpenQuestion(QUESTION_ID, "권한 거부 시 대체 흐름을 둘지 검토 필요")),
                    "권한 거부 흐름을 PM과 확정한 뒤 화면 카피를 수정하세요.")));

    mockMvc
        .perform(get("/api/mobile/tasks/{taskId}", TASK_ID).principal(principal))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(TASK_ID.toString()))
        .andExpect(jsonPath("$.project_id").value(PROJECT_ID.toString()))
        .andExpect(jsonPath("$.title").value("1차 와이어프레임"))
        .andExpect(jsonPath("$.status").value("todo"))
        .andExpect(jsonPath("$.role").value("frontend"))
        .andExpect(jsonPath("$.assignee.id").value(assigneeId.toString()))
        .andExpect(jsonPath("$.assignee.name").value("김규일"))
        .andExpect(
            jsonPath("$.assignee.avatar_url").value("https://lh3.googleusercontent.com/a/gyuil"))
        .andExpect(jsonPath("$.priority").value("medium"))
        .andExpect(jsonPath("$.purpose").value("이번 범위의 화면 흐름을 정리한다"))
        .andExpect(jsonPath("$.checklist.completed_count").value(1))
        .andExpect(jsonPath("$.checklist.total_count").value(2))
        .andExpect(jsonPath("$.checklist.items[0].id").value(doneItemId.toString()))
        .andExpect(jsonPath("$.checklist.items[0].completed").value(true))
        .andExpect(jsonPath("$.checklist.items[1].completed").value(false))
        .andExpect(jsonPath("$.materials.length()").value(1))
        .andExpect(jsonPath("$.materials[0].id").value(MATERIAL_ID.toString()))
        .andExpect(jsonPath("$.materials[0].title").value("권한 요청 화면 v2"))
        .andExpect(jsonPath("$.materials[0].summary").value("설명 문구 변경"))
        .andExpect(jsonPath("$.materials[0].source").value("figma"))
        .andExpect(jsonPath("$.materials[0].occurred_at").exists())
        .andExpect(jsonPath("$.materials[0].source_url").value("https://figma.example/p"))
        .andExpect(jsonPath("$.open_questions.length()").value(1))
        .andExpect(jsonPath("$.open_questions[0].id").value(QUESTION_ID.toString()))
        .andExpect(jsonPath("$.open_questions[0].body").value("권한 거부 시 대체 흐름을 둘지 검토 필요"))
        .andExpect(jsonPath("$.next_action").value("권한 거부 흐름을 PM과 확정한 뒤 화면 카피를 수정하세요."))
        // 원장이 아직 열려 있으면 앱은 이 값을 보고 재조회한다(설계 7.2절).
        .andExpect(jsonPath("$.draft_status").value("generating"));
  }

  @Test
  void getTaskDetailReturnsNullAssigneePurposeAndEmptyChecklist() throws Exception {
    when(projectTaskService.getTaskDetail(TASK_ID, USER_ID))
        .thenReturn(
            new MobileTaskDetailView(
                DraftStatus.READY,
                new MobileTaskDetail(
                    TASK_ID,
                    PROJECT_ID,
                    "빈 상세",
                    "todo",
                    "pm",
                    null,
                    "low",
                    null,
                    List.of(),
                    List.of(),
                    List.of(),
                    null)));

    mockMvc
        .perform(get("/api/mobile/tasks/{taskId}", TASK_ID).principal(principal))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.assignee").value((Object) null))
        .andExpect(jsonPath("$.purpose").value((Object) null))
        .andExpect(jsonPath("$.checklist.completed_count").value(0))
        .andExpect(jsonPath("$.checklist.total_count").value(0))
        .andExpect(jsonPath("$.checklist.items.length()").value(0))
        .andExpect(jsonPath("$.materials.length()").value(0))
        .andExpect(jsonPath("$.open_questions.length()").value(0))
        .andExpect(jsonPath("$.next_action").value((Object) null))
        .andExpect(jsonPath("$.draft_status").value("ready"));
  }

  @Test
  void updateTaskReturnsNoContent() throws Exception {
    mockMvc
        .perform(
            patch("/api/mobile/tasks/{taskId}", TASK_ID)
                .principal(principal)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"title\":\"수정된 제목\",\"role\":\"backend\",\"assignee_id\":null,"
                        + "\"priority\":\"high\",\"status\":\"in_progress\",\"purpose\":\"수정한 목적\","
                        + "\"checklist_items\":[{\"title\":\"기준\",\"completed\":true}]}"))
        .andExpect(status().isNoContent());
  }

  @Test
  void updateTaskPassesChecklistCompletedToService() throws Exception {
    mockMvc
        .perform(
            patch("/api/mobile/tasks/{taskId}", TASK_ID)
                .principal(principal)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"title\":\"제목\",\"role\":\"pm\",\"priority\":\"medium\",\"status\":\"todo\","
                        + "\"checklist_items\":[{\"title\":\"체크됨\",\"completed\":true},"
                        + "{\"title\":\"생략\"}]}"))
        .andExpect(status().isNoContent());

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<ChecklistEdit>> captor = ArgumentCaptor.forClass(List.class);
    verify(projectTaskService)
        .updateTask(
            eq(TASK_ID), eq(USER_ID), any(), any(), any(), any(), any(), any(), captor.capture());
    assertThat(captor.getValue()).extracting(ChecklistEdit::completed).containsExactly(true, false);
  }

  @Test
  void updateTaskAllowsEmptyTitle() throws Exception {
    mockMvc
        .perform(
            patch("/api/mobile/tasks/{taskId}", TASK_ID)
                .principal(principal)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"title\":\"\",\"role\":\"pm\",\"priority\":\"medium\","
                        + "\"status\":\"todo\",\"checklist_items\":[]}"))
        .andExpect(status().isNoContent());
  }

  @Test
  void updateTaskRejectsMoreThanFiveChecklistItems() throws Exception {
    mockMvc
        .perform(
            patch("/api/mobile/tasks/{taskId}", TASK_ID)
                .principal(principal)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"title\":\"제목\",\"role\":\"pm\",\"priority\":\"medium\",\"status\":\"todo\","
                        + "\"checklist_items\":[{\"title\":\"1\"},{\"title\":\"2\"},{\"title\":\"3\"},"
                        + "{\"title\":\"4\"},{\"title\":\"5\"},{\"title\":\"6\"}]}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void updateTaskRejectsTitleLongerThan15() throws Exception {
    String title = "가".repeat(16);
    mockMvc
        .perform(
            patch("/api/mobile/tasks/{taskId}", TASK_ID)
                .principal(principal)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"title\":\""
                        + title
                        + "\",\"role\":\"pm\",\"priority\":\"medium\",\"status\":\"todo\","
                        + "\"checklist_items\":[]}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void updateTaskRejectsPurposeLongerThan300() throws Exception {
    String purpose = "가".repeat(301);
    mockMvc
        .perform(
            patch("/api/mobile/tasks/{taskId}", TASK_ID)
                .principal(principal)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"title\":\"제목\",\"role\":\"pm\",\"priority\":\"medium\",\"status\":\"todo\","
                        + "\"purpose\":\""
                        + purpose
                        + "\",\"checklist_items\":[]}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void updateTaskRejectsChecklistTitleLongerThan50() throws Exception {
    String itemTitle = "가".repeat(51);
    mockMvc
        .perform(
            patch("/api/mobile/tasks/{taskId}", TASK_ID)
                .principal(principal)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"title\":\"제목\",\"role\":\"pm\",\"priority\":\"medium\",\"status\":\"todo\","
                        + "\"checklist_items\":[{\"title\":\""
                        + itemTitle
                        + "\"}]}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void toggleChecklistItemReturnsChecklistSummary() throws Exception {
    UUID itemId = UUID.randomUUID();
    UUID otherId = UUID.randomUUID();
    when(projectTaskService.toggleChecklistItem(TASK_ID, USER_ID, itemId, true))
        .thenReturn(
            new MobileTaskDetail(
                TASK_ID,
                PROJECT_ID,
                "제목",
                "todo",
                "pm",
                null,
                "medium",
                null,
                List.of(
                    new TaskDetail.ChecklistItem(itemId, "바꾼 기준", true),
                    new TaskDetail.ChecklistItem(otherId, "다른 기준", false)),
                List.of(),
                List.of(),
                null));

    mockMvc
        .perform(
            patch("/api/mobile/tasks/{taskId}/checklist-items/{itemId}", TASK_ID, itemId)
                .principal(principal)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"completed\":true}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.checklist.completed_count").value(1))
        .andExpect(jsonPath("$.checklist.total_count").value(2))
        .andExpect(jsonPath("$.checklist.item.id").value(itemId.toString()))
        .andExpect(jsonPath("$.checklist.item.completed").value(true));
  }

  @Test
  void toggleChecklistItemRejectsMissingCompleted() throws Exception {
    mockMvc
        .perform(
            patch("/api/mobile/tasks/{taskId}/checklist-items/{itemId}", TASK_ID, UUID.randomUUID())
                .principal(principal)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isBadRequest());
  }

  @TestConfiguration
  static class ApiVersioningTestConfig implements WebMvcConfigurer {
    @Override
    public void configureApiVersioning(ApiVersionConfigurer configurer) {
      configurer.useRequestHeader("API-Version").addSupportedVersions("1").setDefaultVersion("1");
    }
  }
}
