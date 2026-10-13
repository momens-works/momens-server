package works.momens.server.source.connection.oauth;

import java.util.List;
import java.util.Map;

/** 토큰 교환의 요청 형식·자격 증명 전달 방식은 값으로 정의하고, 응답 해석만 provider별 함수로 분리합니다. */
record ProviderDefinition(
    String sourceType,
    String authorizeEndpoint,
    String tokenEndpoint,
    List<String> scopes,
    Map<String, String> authorizeParams,
    TokenBodyFormat tokenBodyFormat,
    ClientAuthStyle clientAuthStyle,
    String identityEndpoint,
    IdentityMethod identityMethod,
    ProviderIdentityMapper identityMapper) {

  enum TokenBodyFormat {
    FORM,
    JSON
  }

  enum ClientAuthStyle {
    REQUEST_BODY,
    BASIC_HEADER
  }

  enum IdentityMethod {
    NONE,
    GET,
    POST
  }
}
