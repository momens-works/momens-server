package works.momens.server.minsu.llm;

import java.util.List;

public record LlmRequest(
    String promptVersion,
    String systemInstruction,
    String dataJson,
    List<LlmResponseField> responseFields) {

  public LlmRequest {
    responseFields = List.copyOf(responseFields);
  }
}
