package works.momens.server.mobile.board;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.security.Principal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
import works.momens.server.project.task.TaskPriority;
import works.momens.server.project.task.TaskSnapshot;

/**
 * 컨트롤러가 경로 변수와 요청 body, Principal을 조합 서비스에 전달하고 명세의 snake_case 응답 형식을 내는지 검증합니다. 검증 실패의 에러 응답 형식은
 * app 예외 핸들러가 담당하므로 여기서는 상태 코드까지만 봅니다. versioning은 모듈 경계상 슬라이스 안에서 동일하게 구성합니다.
 */
@WebMvcTest(ProjectTaskController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(ProjectTaskControllerTest.ApiVersioningTestConfig.class)
class ProjectTaskControllerTest {

  @Autowired private MockMvc mockMvc;
  @MockitoBean private ProjectTaskService projectTaskService;

  private static final UUID USER_ID = UUID.fromString("5d2f7f3a-5db1-4f2c-8b9e-13607dd1f5e8");
  private static final UUID PROJECT_ID = UUID.fromString("30d9e9fe-f43b-4097-a88e-dc19f0a5b025");
  private static final UUID ASSIGNEE_ID = UUID.fromString("9d4a5b1c-2e3f-4a5b-8c9d-0e1f2a3b4c5d");
  private final Principal principal = USER_ID::toString;

  @Test
  void getBoardReturnsSnakeCaseGroups() throws Exception {
    UUID taskId = UUID.randomUUID();
    when(projectTaskService.getBoard(eq(PROJECT_ID), eq(USER_ID)))
        .thenReturn(
            List.of(
                new MobileTaskGroup(
                    MobileTaskStatus.TODO,
                    List.of(
                        new MobileTaskCard(
                            taskId,
                            "투두 태스크",
                            "frontend",
                            new MobileTaskCard.Assignee(ASSIGNEE_ID, "홍길동"),
                            "low",
                            LocalDate.of(2026, 9, 25),
                            2))),
                new MobileTaskGroup(MobileTaskStatus.IN_PROGRESS, List.of()),
                new MobileTaskGroup(MobileTaskStatus.DONE, List.of())));

    mockMvc
        .perform(
            get("/api/mobile/projects/{projectId}/tasks", PROJECT_ID)
                .principal(principal)
                .header("API-Version", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.title").value("프로젝트 작업"))
        .andExpect(jsonPath("$.groups.length()").value(3))
        .andExpect(jsonPath("$.groups[0].group_key").value("todo"))
        .andExpect(jsonPath("$.groups[0].label").value("할 일"))
        .andExpect(jsonPath("$.groups[0].count").value(1))
        .andExpect(jsonPath("$.groups[0].tasks[0].id").value(taskId.toString()))
        .andExpect(jsonPath("$.groups[0].tasks[0].role").value("frontend"))
        .andExpect(jsonPath("$.groups[0].tasks[0].assignee.id").value(ASSIGNEE_ID.toString()))
        .andExpect(jsonPath("$.groups[0].tasks[0].assignee.name").value("홍길동"))
        .andExpect(jsonPath("$.groups[0].tasks[0].due_date").value("2026-09-25"))
        .andExpect(jsonPath("$.groups[0].tasks[0].material_count").value(2))
        .andExpect(jsonPath("$.groups[1].tasks.length()").value(0));
  }

  @Test
  void createTaskReturnsCreatedTask() throws Exception {
    UUID taskId = UUID.randomUUID();
    when(projectTaskService.createTask(
            eq(PROJECT_ID), eq(USER_ID), eq("제목"), any(), eq(TaskPriority.MEDIUM)))
        .thenReturn(
            new TaskSnapshot(
                taskId,
                UUID.randomUUID(),
                PROJECT_ID,
                null,
                "MOM-0001",
                "제목",
                null,
                "todo",
                "medium",
                "pm",
                null,
                null,
                Instant.EPOCH,
                Instant.EPOCH,
                0L));

    mockMvc
        .perform(
            post("/api/mobile/projects/{projectId}/tasks", PROJECT_ID)
                .principal(principal)
                .header("API-Version", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"제목\",\"role\":\"pm\",\"priority\":\"medium\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.task.id").value(taskId.toString()))
        .andExpect(jsonPath("$.task.project_id").value(PROJECT_ID.toString()))
        .andExpect(jsonPath("$.task.status").value("todo"))
        .andExpect(jsonPath("$.task.role").value("pm"));
  }

  @Test
  @DisplayName("urgent로 태스크를 생성하면 그대로 저장하고 응답에서는 high로 표시합니다")
  void createTaskAcceptsUrgentAndRespondsHigh() throws Exception {
    UUID taskId = UUID.randomUUID();
    when(projectTaskService.createTask(
            eq(PROJECT_ID), eq(USER_ID), eq("제목"), any(), eq(TaskPriority.URGENT)))
        .thenReturn(
            new TaskSnapshot(
                taskId,
                UUID.randomUUID(),
                PROJECT_ID,
                null,
                "MOM-0001",
                "제목",
                null,
                "todo",
                "urgent",
                "pm",
                null,
                null,
                Instant.EPOCH,
                Instant.EPOCH,
                0L));

    mockMvc
        .perform(
            post("/api/mobile/projects/{projectId}/tasks", PROJECT_ID)
                .principal(principal)
                .header("API-Version", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"제목\",\"role\":\"pm\",\"priority\":\"urgent\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.task.id").value(taskId.toString()))
        .andExpect(jsonPath("$.task.project_id").value(PROJECT_ID.toString()))
        .andExpect(jsonPath("$.task.status").value("todo"))
        .andExpect(jsonPath("$.task.priority").value("high"));
  }

  @Test
  void createTaskRejectsBlankTitle() throws Exception {
    mockMvc
        .perform(
            post("/api/mobile/projects/{projectId}/tasks", PROJECT_ID)
                .principal(principal)
                .header("API-Version", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"  \",\"role\":\"pm\",\"priority\":\"medium\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void createTaskRejectsTitleOverFifteen() throws Exception {
    String sixteenChars = "가".repeat(16);
    mockMvc
        .perform(
            post("/api/mobile/projects/{projectId}/tasks", PROJECT_ID)
                .principal(principal)
                .header("API-Version", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"title\":\"" + sixteenChars + "\",\"role\":\"pm\",\"priority\":\"medium\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void createTaskRejectsMissingRoleOrPriority() throws Exception {
    mockMvc
        .perform(
            post("/api/mobile/projects/{projectId}/tasks", PROJECT_ID)
                .principal(principal)
                .header("API-Version", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"제목\",\"priority\":\"medium\"}"))
        .andExpect(status().isBadRequest());

    mockMvc
        .perform(
            post("/api/mobile/projects/{projectId}/tasks", PROJECT_ID)
                .principal(principal)
                .header("API-Version", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"제목\",\"role\":\"pm\"}"))
        .andExpect(status().isBadRequest());

    mockMvc
        .perform(
            post("/api/mobile/projects/{projectId}/tasks", PROJECT_ID)
                .principal(principal)
                .header("API-Version", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"제목\",\"role\":\"\",\"priority\":\"medium\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void createTaskRejectsUnknownRoleAndPriority() throws Exception {
    mockMvc
        .perform(
            post("/api/mobile/projects/{projectId}/tasks", PROJECT_ID)
                .principal(principal)
                .header("API-Version", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"제목\",\"role\":\"ceo\",\"priority\":\"medium\"}"))
        .andExpect(status().isBadRequest());

    mockMvc
        .perform(
            post("/api/mobile/projects/{projectId}/tasks", PROJECT_ID)
                .principal(principal)
                .header("API-Version", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"제목\",\"role\":\"pm\",\"priority\":\"critical\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void createTaskRejectsRolesOutsideCreationFour() throws Exception {
    for (String role : new String[] {"android", "qa"}) {
      mockMvc
          .perform(
              post("/api/mobile/projects/{projectId}/tasks", PROJECT_ID)
                  .principal(principal)
                  .header("API-Version", "1")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"title\":\"제목\",\"role\":\"" + role + "\",\"priority\":\"medium\"}"))
          .andExpect(status().isBadRequest());
    }
  }

  @TestConfiguration
  static class ApiVersioningTestConfig implements WebMvcConfigurer {
    @Override
    public void configureApiVersioning(ApiVersionConfigurer configurer) {
      configurer.useRequestHeader("API-Version").addSupportedVersions("1").setDefaultVersion("1");
    }
  }
}
