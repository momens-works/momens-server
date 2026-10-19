package works.momens.server.web.task;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.security.Principal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.config.annotation.ApiVersionConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import works.momens.server.project.task.TaskSnapshot;

@WebMvcTest(TaskReadController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(TaskReadControllerTest.ApiVersioningTestConfig.class)
class TaskReadControllerTest {

  private static final UUID USER_ID = UUID.fromString("5d2f7f3a-5db1-4f2c-8b9e-13607dd1f5e8");
  private static final UUID TASK_ID = UUID.fromString("30d9e9fe-f43b-4097-a88e-dc19f0a5b025");
  private final Principal principal = USER_ID::toString;

  @Autowired private MockMvc mockMvc;
  @MockitoBean private TaskReadService taskReadService;
  @MockitoBean private TaskUpdateService taskUpdateService;

  @Test
  @DisplayName("태스크 상세는 레거시 필드를 snake_case로 응답한다")
  void getReturnsLegacyTaskShapeInSnakeCase() throws Exception {
    when(taskReadService.get(TASK_ID, USER_ID)).thenReturn(task());

    mockMvc
        .perform(
            get("/api/tasks/{taskId}", TASK_ID).principal(principal).header("API-Version", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(TASK_ID.toString()))
        .andExpect(jsonPath("$.project_id").value("39d9e9fe-f43b-4097-a88e-dc19f0a5b025"))
        .andExpect(jsonPath("$.milestone_id").value("40d9e9fe-f43b-4097-a88e-dc19f0a5b025"))
        .andExpect(jsonPath("$.label").value("MOM-861"))
        .andExpect(jsonPath("$.due_date").value("2026-08-31"))
        .andExpect(jsonPath("$.created_at").value("2026-08-20T00:00:00Z"));
  }

  private static TaskSnapshot task() {
    return new TaskSnapshot(
        TASK_ID,
        UUID.fromString("31d9e9fe-f43b-4097-a88e-dc19f0a5b025"),
        UUID.fromString("39d9e9fe-f43b-4097-a88e-dc19f0a5b025"),
        UUID.fromString("40d9e9fe-f43b-4097-a88e-dc19f0a5b025"),
        "MOM-861",
        "웹 task read",
        null,
        "todo",
        "medium",
        null,
        null,
        LocalDate.parse("2026-08-31"),
        Instant.parse("2026-08-20T00:00:00Z"),
        Instant.parse("2026-08-20T00:00:00Z"),
        0L);
  }

  @TestConfiguration
  static class ApiVersioningTestConfig implements WebMvcConfigurer {
    @Override
    public void configureApiVersioning(ApiVersionConfigurer configurer) {
      configurer.useRequestHeader("API-Version").addSupportedVersions("1").setDefaultVersion("1");
    }
  }
}
