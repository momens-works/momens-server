package works.momens.server.minsu.llm.google;

import com.google.genai.Client;
import com.google.genai.types.Candidate;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import com.google.genai.types.Type;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import works.momens.server.minsu.llm.LlmRequest;
import works.momens.server.minsu.llm.LlmResponse;
import works.momens.server.minsu.llm.LlmResponseField;
import works.momens.server.minsu.llm.ModelSelection;

final class DefaultGoogleSdkClient implements GoogleSdkClient {

  private final Client client;

  DefaultGoogleSdkClient(Client client) {
    this.client = client;
  }

  @Override
  public LlmResponse generate(ModelSelection selection, LlmRequest request, Duration timeout) {
    GenerateContentConfig config =
        GenerateContentConfig.builder()
            .candidateCount(1)
            .responseMimeType("application/json")
            .responseSchema(responseSchema(request.responseFields()))
            .systemInstruction(Content.fromParts(Part.fromText(request.systemInstruction())))
            // 요청별 timeout(9.1절). client는 하나를 캐시해 재사용하므로 client-level 값으로는 동기와
            // 비동기가 다른 값을 가질 수 없다. apiVersion·retryOptions까지 포함해 통째로 넘기는 것은
            // 요청 옵션과 client 옵션의 병합 방식에 기대지 않기 위해서다.
            .httpOptions(DefaultGoogleClientFactory.httpOptions(timeout))
            .build();
    GenerateContentResponse response =
        client.models.generateContent(selection.model(), request.dataJson(), config);
    return map(response);
  }

  @Override
  public void close() {
    client.close();
  }

  static LlmResponse map(GenerateContentResponse response) {
    List<Candidate> candidates = response.candidates().orElse(List.of());
    if (candidates.isEmpty()) {
      return new LlmResponse(
          false,
          "",
          "",
          response.responseId().orElse(""),
          tokenUsage(response.usageMetadata().orElse(null)));
    }
    Candidate candidate = candidates.getFirst();
    String text =
        candidate.content().flatMap(Content::parts).orElse(List.of()).stream()
            .map(part -> part.text().orElse(""))
            .collect(Collectors.joining());
    return new LlmResponse(
        true,
        candidate.finishReason().map(Object::toString).orElse(""),
        text,
        response.responseId().orElse(""),
        tokenUsage(response.usageMetadata().orElse(null)));
  }

  static Schema responseSchema(List<LlmResponseField> fields) {
    Map<String, Schema> properties = new LinkedHashMap<>();
    for (LlmResponseField field : fields) {
      Schema.Builder property = Schema.builder().type(Type.Known.STRING);
      if (field.description() != null) {
        property.description(field.description());
      }
      if (!field.allowedValues().isEmpty()) {
        property.enum_(field.allowedValues());
      }
      properties.put(field.name(), property.build());
    }
    List<String> names = List.copyOf(properties.keySet());
    return Schema.builder()
        .type(Type.Known.OBJECT)
        .properties(properties)
        .required(names)
        .propertyOrdering(names)
        .build();
  }

  private static LlmResponse.TokenUsage tokenUsage(GenerateContentResponseUsageMetadata usage) {
    if (usage == null) {
      return LlmResponse.TokenUsage.EMPTY;
    }
    return new LlmResponse.TokenUsage(
        usage.promptTokenCount().orElse(0),
        usage.candidatesTokenCount().orElse(0),
        usage.thoughtsTokenCount().orElse(0),
        usage.totalTokenCount().orElse(0));
  }
}
