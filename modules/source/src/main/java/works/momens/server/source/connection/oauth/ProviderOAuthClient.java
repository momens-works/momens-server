package works.momens.server.source.connection.oauth;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import works.momens.server.source.connection.oauth.ProviderDefinition.ClientAuthStyle;
import works.momens.server.source.connection.oauth.ProviderDefinition.IdentityMethod;
import works.momens.server.source.connection.oauth.ProviderDefinition.TokenBodyFormat;

/**
 * provider와 토큰을 교환하고 외부 계정 정보를 조회합니다.
 *
 * <p>요청 형식과 자격 증명 전달 방식은 {@link ProviderDefinition}에 정의된 값을 사용하므로 이 클래스에는 provider별 분기가 없습니다. 사용자
 * 정보를 별도로 조회하지 않는 provider는 빈 응답을 반환하며, 이 경우 토큰 응답에서 외부 계정 정보를 추출합니다.
 */
class ProviderOAuthClient {

  private static final Class<Map<String, Object>> BODY_TYPE = castBodyType();

  private final RestClient restClient;

  ProviderOAuthClient(RestClient restClient) {
    this.restClient = restClient;
  }

  Map<String, Object> exchange(OAuthProvider provider, String code) {
    ProviderDefinition definition = provider.definition();
    RestClient.RequestBodySpec request =
        restClient
            .post()
            .uri(definition.tokenEndpoint())
            .accept(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.USER_AGENT, "momens-server");
    if (definition.clientAuthStyle() == ClientAuthStyle.BASIC_HEADER) {
      request = request.header(HttpHeaders.AUTHORIZATION, basicAuth(provider));
    }
    Map<String, Object> response =
        definition.tokenBodyFormat() == TokenBodyFormat.JSON
            ? request
                .contentType(MediaType.APPLICATION_JSON)
                .body(jsonTokenBody(provider, code))
                .retrieve()
                .body(BODY_TYPE)
            : request
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(formTokenBody(provider, code))
                .retrieve()
                .body(BODY_TYPE);
    return response == null ? Map.of() : response;
  }

  Map<String, Object> fetchIdentity(OAuthProvider provider, Map<String, Object> tokenResponse) {
    ProviderDefinition definition = provider.definition();
    if (definition.identityMethod() == IdentityMethod.NONE) {
      return Map.of();
    }
    String accessToken = String.valueOf(tokenResponse.get("access_token"));
    RestClient.RequestHeadersSpec<?> request =
        definition.identityMethod() == IdentityMethod.POST
            ? restClient.post().uri(definition.identityEndpoint())
            : restClient.get().uri(definition.identityEndpoint());
    Map<String, Object> body =
        request
            .accept(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
            .header(HttpHeaders.USER_AGENT, "momens-server")
            .retrieve()
            .body(BODY_TYPE);
    return body == null ? Map.of() : body;
  }

  private static Map<String, Object> jsonTokenBody(OAuthProvider provider, String code) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("grant_type", "authorization_code");
    payload.put("code", code);
    payload.put("redirect_uri", provider.redirectUri());
    if (provider.definition().clientAuthStyle() == ClientAuthStyle.REQUEST_BODY) {
      payload.put("client_id", provider.clientId());
      payload.put("client_secret", provider.clientSecret());
    }
    return payload;
  }

  private static MultiValueMap<String, String> formTokenBody(OAuthProvider provider, String code) {
    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("grant_type", "authorization_code");
    form.add("code", code);
    form.add("redirect_uri", provider.redirectUri());
    if (provider.definition().clientAuthStyle() == ClientAuthStyle.REQUEST_BODY) {
      form.add("client_id", provider.clientId());
      form.add("client_secret", provider.clientSecret());
    }
    return form;
  }

  /**
   * RFC 6749 2.3.1은 client id와 secret을 각각 {@code application/x-www-form-urlencoded}로 인코딩한 뒤 결합하도록
   * 정의합니다. 레거시는 인코딩 없이 결합하지만, {@code :}나 {@code +}가 포함된 자격 증명에서 값이 잘못 전달되므로 표준을 따릅니다.
   */
  static String basicAuth(OAuthProvider provider) {
    String raw = formEncode(provider.clientId()) + ":" + formEncode(provider.clientSecret());
    return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
  }

  private static String formEncode(String value) {
    return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
  }

  @SuppressWarnings("unchecked")
  private static Class<Map<String, Object>> castBodyType() {
    return (Class<Map<String, Object>>) (Class<?>) Map.class;
  }
}
