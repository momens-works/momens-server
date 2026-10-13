package works.momens.server.auth.internal.google;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;
import works.momens.server.auth.AuthErrorCode;
import works.momens.server.auth.internal.config.AuthProperties;
import works.momens.server.common.api.BusinessException;

/**
 * 웹 Authorization Code 플로우의 서버측 Google 통신: consent URL 생성, code→token 교환, userinfo 조회.
 * email_verified 게이트는 userinfo 응답에서 적용합니다.
 */
@Component
public class GoogleOAuthClient {

  private final RestClient restClient;
  private final AuthProperties.Web.GoogleOauth config;

  GoogleOAuthClient(RestClient googleOAuthRestClient, AuthProperties properties) {
    this.restClient = googleOAuthRestClient;
    this.config = properties.web().googleOauth();
  }

  public String authorizationUrl(String state, String codeChallenge) {
    return UriComponentsBuilder.fromUriString(config.authorizationUri())
        .queryParam("client_id", config.clientId())
        .queryParam("redirect_uri", config.redirectUri())
        .queryParam("response_type", "code")
        .queryParam("scope", String.join(" ", config.scopes()))
        .queryParam("state", state)
        .queryParam("code_challenge", codeChallenge)
        .queryParam("code_challenge_method", "S256")
        .build()
        .encode()
        .toUriString();
  }

  public String exchangeCode(String code, String codeVerifier) {
    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("grant_type", "authorization_code");
    form.add("client_id", config.clientId());
    form.add("client_secret", config.clientSecret());
    form.add("redirect_uri", config.redirectUri());
    form.add("code", code);
    form.add("code_verifier", codeVerifier);

    TokenResponse response;
    try {
      response =
          restClient
              .post()
              .uri(config.tokenUri())
              .contentType(MediaType.APPLICATION_FORM_URLENCODED)
              .body(form)
              .retrieve()
              .body(TokenResponse.class);
    } catch (RestClientException e) {
      throw new BusinessException(AuthErrorCode.AUTH_OAUTH_EXCHANGE_FAILED);
    }
    if (response == null || response.accessToken() == null || response.accessToken().isBlank()) {
      throw new BusinessException(AuthErrorCode.AUTH_OAUTH_EXCHANGE_FAILED);
    }
    return response.accessToken();
  }

  /** Google access token으로 userinfo를 조회합니다. email_verified가 아니면 거부합니다. */
  public GoogleUserInfo fetchUserInfo(String googleAccessToken) {
    UserInfoResponse response;
    try {
      response =
          restClient
              .get()
              .uri(config.userinfoUri())
              .header("Authorization", "Bearer " + googleAccessToken)
              .retrieve()
              .body(UserInfoResponse.class);
    } catch (RestClientException e) {
      throw new BusinessException(AuthErrorCode.AUTH_OAUTH_EXCHANGE_FAILED);
    }
    // email_verified가 누락된 응답은 정상 응답으로 볼 수 없으므로,
    // 미검증 계정이 아니라 토큰 교환 실패로 처리합니다.
    // 사용자 식별 키인 sub이 누락된 경우도 로그인을 이어갈 수 없으므로
    // 같은 기준으로 처리합니다(ADR-0016).
    if (response == null
        || response.sub() == null
        || response.sub().isBlank()
        || response.email() == null
        || response.email().isBlank()
        || response.emailVerified() == null) {
      throw new BusinessException(AuthErrorCode.AUTH_OAUTH_EXCHANGE_FAILED);
    }
    if (!response.emailVerified()) {
      throw new BusinessException(AuthErrorCode.AUTH_GOOGLE_EMAIL_NOT_VERIFIED);
    }
    return new GoogleUserInfo(
        response.sub(), response.email(), response.name(), response.picture());
  }

  private record TokenResponse(@JsonProperty("access_token") String accessToken) {}

  private record UserInfoResponse(
      String sub,
      String email,
      @JsonProperty("email_verified") Boolean emailVerified,
      String name,
      String picture) {}
}
