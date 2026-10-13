package works.momens.server.source.connection.oauth;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;

/** 승인 URL은 레거시와 같은 정렬·인코딩을 유지합니다. scope는 값이 있을 때만 포함합니다. */
record OAuthProvider(
    ProviderDefinition definition, String clientId, String clientSecret, String redirectUri) {

  String sourceType() {
    return definition.sourceType();
  }

  boolean isConfigured() {
    return hasText(clientId) && hasText(clientSecret) && hasText(redirectUri);
  }

  String authorizeUrl(String state) {
    Map<String, String> params = new TreeMap<>(definition.authorizeParams());
    params.put("client_id", clientId);
    params.put("redirect_uri", redirectUri);
    params.put("response_type", "code");
    params.put("state", state);
    if (!definition.scopes().isEmpty()) {
      params.put("scope", String.join(" ", definition.scopes()));
    }
    StringBuilder query = new StringBuilder();
    params.forEach(
        (name, value) -> {
          if (!query.isEmpty()) {
            query.append('&');
          }
          query.append(encode(name)).append('=').append(encode(value));
        });
    return definition.authorizeEndpoint() + "?" + query;
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }
}
