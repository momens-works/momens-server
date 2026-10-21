package works.momens.server.source.connection.oauth;

import java.time.Instant;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Figma access token 갱신의 HTTP 경계입니다. provider 응답 본문과 예외 원문은 token을 포함할 수 있으므로 실패 사유에 담지 않습니다.
 *
 * <p>Figma의 갱신 응답에는 새 refresh token이 없습니다. refresh token은 재사용하는 값이므로 호출자가 기존 값을 유지합니다.
 */
@RequiredArgsConstructor
class FigmaTokenRefreshClient {

  private final RestClient restClient;

  RefreshedToken refresh(OAuthProvider provider, String refreshToken) {
    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("refresh_token", refreshToken);
    try {
      return restClient
          .post()
          .uri("/v1/oauth/refresh")
          .header(HttpHeaders.AUTHORIZATION, ProviderOAuthClient.basicAuth(provider))
          .contentType(MediaType.APPLICATION_FORM_URLENCODED)
          .accept(MediaType.APPLICATION_JSON)
          .body(form)
          .exchange(
              (request, response) -> {
                int status = response.getStatusCode().value();
                if (status == 400 || status == 401 || status == 403) {
                  throw new RefreshFailedException(true, "status=" + status);
                }
                if (status < 200 || status >= 300) {
                  throw new RefreshFailedException(false, "status=" + status);
                }
                return toToken(response.bodyTo(new ParameterizedTypeReference<>() {}));
              });
    } catch (RestClientException e) {
      throw new RefreshFailedException(false, e.getClass().getSimpleName());
    }
  }

  private static RefreshedToken toToken(Map<String, Object> body) {
    Object accessToken = body == null ? null : body.get("access_token");
    if (!(accessToken instanceof String value) || value.isBlank()) {
      throw new RefreshFailedException(false, "invalid_response");
    }
    Object tokenType = body.get("token_type");
    return new RefreshedToken(
        value,
        tokenType instanceof String type && !type.isBlank() ? type : null,
        SourceInstallerImpl.expiresAt(body));
  }

  record RefreshedToken(String accessToken, String tokenType, Instant expiresAt) {}

  /** 재인증이 필요하면 다시 시도해도 성공하지 않습니다. 그 외 실패는 다음 주기의 재시도로 회복될 수 있습니다. */
  static class RefreshFailedException extends RuntimeException {

    private final boolean reauthRequired;

    RefreshFailedException(boolean reauthRequired, String reason) {
      super(reason, null, false, false);
      this.reauthRequired = reauthRequired;
    }

    boolean reauthRequired() {
      return reauthRequired;
    }
  }
}
