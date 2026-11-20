package works.momens.server.source.connection.oauth;

import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import works.momens.server.common.api.BusinessException;
import works.momens.server.source.SourceErrorCode;

/** Figma webhook lifecycle의 HTTP 경계입니다. provider 응답 본문은 오류나 로그에 포함하지 않습니다. */
@RequiredArgsConstructor
class FigmaWebhookClient {

  private final RestClient restClient;

  String create(String token, String teamId, UUID workspaceId, FigmaWebhookProperties properties) {
    Map<String, Object> body;
    try {
      body =
          restClient
              .post()
              .uri("/v2/webhooks")
              .headers(headers -> headers.setBearerAuth(token))
              .contentType(MediaType.APPLICATION_JSON)
              .accept(MediaType.APPLICATION_JSON)
              .body(
                  Map.of(
                      "event_type",
                      "FILE_COMMENT",
                      "context",
                      "team",
                      "context_id",
                      teamId,
                      "endpoint",
                      properties.endpoint(),
                      "passcode",
                      properties.passcode(),
                      "description",
                      "momens FILE_COMMENT webhook (workspace " + workspaceId + ")"))
              .retrieve()
              .body(new ParameterizedTypeReference<Map<String, Object>>() {});
    } catch (RestClientException e) {
      // 예외 원문에는 provider 응답과 token이 들어 있을 수 있으므로 전파하지 않습니다.
      throw new BusinessException(SourceErrorCode.SOURCE_FIGMA_WEBHOOK_FAILED, Map.of());
    }
    Object id = body == null ? null : body.get("id");
    if (!(id instanceof String value) || value.isBlank()) {
      throw new BusinessException(SourceErrorCode.SOURCE_FIGMA_WEBHOOK_FAILED, Map.of());
    }
    return value.strip();
  }

  int delete(String accessToken, String webhookId) {
    return restClient
        .delete()
        .uri("/v2/webhooks/{id}", webhookId)
        .headers(headers -> headers.setBearerAuth(accessToken))
        .accept(MediaType.APPLICATION_JSON)
        .exchange((request, response) -> response.getStatusCode().value());
  }
}
