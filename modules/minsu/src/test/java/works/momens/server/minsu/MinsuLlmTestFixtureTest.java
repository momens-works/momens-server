package works.momens.server.minsu;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import works.momens.server.project.task.TaskPriority;
import works.momens.server.project.task.TaskRole;

class MinsuLlmTestFixtureTest {

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final MinsuLlmTestFixture fixture = new MinsuLlmTestFixture();

  @Test
  void serializesSpecialCharactersInTitle() throws Exception {
    String title = "따옴표 \" 역슬래시 \\ 줄바꿈\n제목";
    fixture.respondWith(new TaskDraft(title, TaskRole.BACKEND, TaskPriority.HIGH));

    JsonNode response = objectMapper.readTree(fixture.generate().text());

    assertThat(response.path("title").asText()).isEqualTo(title);
    assertThat(response.path("role").asText()).isEqualTo("backend");
    assertThat(response.path("priority").asText()).isEqualTo("high");
  }
}
