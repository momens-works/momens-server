package works.momens.server.minsu.draft.prompt;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import works.momens.server.minsu.SignalTaskDraftInput;
import works.momens.server.minsu.draft.json.MinsuJson;
import works.momens.server.minsu.llm.LlmRequest;
import works.momens.server.minsu.llm.LlmResponseField;
import works.momens.server.project.task.TaskPriority;
import works.momens.server.project.task.TaskRole;

@Component
public final class SignalTaskDraftPrompt {

  static final String VERSION = "signal-task-draft-v1";
  static final int MAX_EVIDENCE_COUNT = 10;
  private static final String RESOURCE = "/prompts/signal-task-draft-v1.txt";

  /**
   * LLM 응답 스키마에서 허용하는 role과 priority 값은 각각 `TaskRole`과 `TaskPriority`를 기준으로 생성합니다. 프롬프트 파일의 값 목록도
   * 렌더링할 때 같은 enum으로 채우므로 두 목록이 서로 달라지지 않습니다. enum에 값이 추가되면 `roleMeaning`과 `priorityMeaning`의
   * switch에서 컴파일 오류가 발생해 설명 누락을 확인할 수 있습니다.
   */
  private static final List<LlmResponseField> RESPONSE_FIELDS =
      List.of(
          new LlmResponseField("title", "공백을 포함해 15자 이내인 한국어 실행 항목", List.of()),
          new LlmResponseField(
              "role", null, Arrays.stream(TaskRole.values()).map(TaskRole::value).toList()),
          new LlmResponseField(
              "priority",
              null,
              Arrays.stream(TaskPriority.values()).map(TaskPriority::value).toList()));

  private final MinsuJson json;
  private final String systemInstruction;

  public SignalTaskDraftPrompt(MinsuJson json) {
    this.json = json;
    this.systemInstruction =
        loadInstruction()
            .replace(
                "{{roleMeanings}}",
                meanings(TaskRole.values(), TaskRole::value, SignalTaskDraftPrompt::roleMeaning))
            .replace(
                "{{priorityMeanings}}",
                meanings(
                    TaskPriority.values(),
                    TaskPriority::value,
                    SignalTaskDraftPrompt::priorityMeaning));
  }

  public LlmRequest render(SignalTaskDraftInput input) {
    List<EvidenceData> evidence =
        input.evidence().stream()
            .map(
                item ->
                    new EvidenceData(
                        trimToNull(item.target()),
                        trimToNull(item.change()),
                        trimToNull(item.impact())))
            .filter(EvidenceData::hasMeaning)
            .limit(MAX_EVIDENCE_COUNT)
            .toList();
    InputData data =
        new InputData(
            trimToNull(input.title()),
            trimToNull(input.type()),
            trimToNull(input.description()),
            trimToNull(input.impact()),
            evidence);
    return new LlmRequest(VERSION, systemInstruction, json.write(data), RESPONSE_FIELDS);
  }

  public static boolean hasSufficientContext(SignalTaskDraftInput input) {
    if (hasText(input.description()) || hasText(input.impact())) {
      return true;
    }
    return input.evidence().stream()
        .anyMatch(
            evidence ->
                hasText(evidence.target())
                    || hasText(evidence.change())
                    || hasText(evidence.impact()));
  }

  private static String loadInstruction() {
    try (InputStream input = SignalTaskDraftPrompt.class.getResourceAsStream(RESOURCE)) {
      if (input == null) {
        throw new IllegalStateException("Signal task draft prompt resource를 찾을 수 없습니다");
      }
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("Signal task draft prompt resource를 읽을 수 없습니다", e);
    }
  }

  private static <E> String meanings(
      E[] values, Function<E, String> value, Function<E, String> meaning) {
    return Arrays.stream(values)
        .map(constant -> "- " + value.apply(constant) + ": " + meaning.apply(constant))
        .collect(Collectors.joining("\n"));
  }

  private static String roleMeaning(TaskRole role) {
    return switch (role) {
      case PM -> "planning, scope, coordination, or decision ownership";
      case DESIGN -> "user experience or visual design work";
      case BACKEND -> "server, data, or infrastructure work";
      case FRONTEND -> "web or mobile client work";
    };
  }

  private static String priorityMeaning(TaskPriority priority) {
    return switch (priority) {
      case LOW -> "can wait without meaningful near-term impact";
      case MEDIUM -> "normal planned work";
      case HIGH -> "prompt action is needed to avoid meaningful impact";
      case URGENT -> "must be reviewed and handled before any other work";
    };
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }

  private static String trimToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private record InputData(
      String title, String type, String description, String impact, List<EvidenceData> evidence) {}

  private record EvidenceData(String target, String change, String impact) {
    boolean hasMeaning() {
      return target != null || change != null || impact != null;
    }
  }
}
