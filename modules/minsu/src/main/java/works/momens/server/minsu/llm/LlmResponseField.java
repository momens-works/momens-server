package works.momens.server.minsu.llm;

import java.util.List;

public record LlmResponseField(String name, String description, List<String> allowedValues) {

  public LlmResponseField {
    allowedValues = List.copyOf(allowedValues);
  }
}
