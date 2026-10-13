package works.momens.server.source.connection.oauth;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import works.momens.server.source.connection.oauth.ProviderDefinition.ClientAuthStyle;
import works.momens.server.source.connection.oauth.ProviderDefinition.IdentityMethod;
import works.momens.server.source.connection.oauth.ProviderDefinition.TokenBodyFormat;

/** provider 이름의 대소문자와 앞뒤 공백을 무시하며, 지원하지 않는 이름은 빈 값으로 반환합니다. */
class OAuthProviderRegistry {

  static final String GITHUB = "GITHUB";
  static final String SLACK = "SLACK";
  static final String NOTION = "NOTION";
  static final String FIGMA = "FIGMA";

  private static final Map<String, ProviderDefinition> DEFINITIONS =
      Map.of(
          GITHUB,
          new ProviderDefinition(
              GITHUB,
              "https://github.com/login/oauth/authorize",
              "https://github.com/login/oauth/access_token",
              List.of("read:user"),
              Map.of(),
              TokenBodyFormat.FORM,
              ClientAuthStyle.REQUEST_BODY,
              "https://api.github.com/user",
              IdentityMethod.GET,
              ProviderIdentities::github),
          SLACK,
          new ProviderDefinition(
              SLACK,
              "https://slack.com/oauth/v2/authorize",
              "https://slack.com/api/oauth.v2.access",
              List.of(),
              Map.of(
                  "scope",
                  "channels:read,channels:history,groups:read,groups:history,team:read,channels:join"),
              TokenBodyFormat.FORM,
              ClientAuthStyle.BASIC_HEADER,
              "https://slack.com/api/auth.test",
              IdentityMethod.POST,
              ProviderIdentities::slack),
          NOTION,
          new ProviderDefinition(
              NOTION,
              "https://api.notion.com/v1/oauth/authorize",
              "https://api.notion.com/v1/oauth/token",
              List.of(),
              Map.of("owner", "user"),
              TokenBodyFormat.JSON,
              ClientAuthStyle.BASIC_HEADER,
              null,
              IdentityMethod.NONE,
              ProviderIdentities::notion),
          FIGMA,
          new ProviderDefinition(
              FIGMA,
              "https://www.figma.com/oauth",
              "https://api.figma.com/v1/oauth/token",
              List.of("file_comments:read", "webhooks:write"),
              Map.of(),
              TokenBodyFormat.FORM,
              ClientAuthStyle.BASIC_HEADER,
              null,
              IdentityMethod.NONE,
              ProviderIdentities::figma));

  private final SourceOAuthProperties properties;

  OAuthProviderRegistry(SourceOAuthProperties properties) {
    this.properties = properties;
  }

  Optional<OAuthProvider> find(String sourceType) {
    String normalized = OAuthStateSigner.normalize(sourceType);
    ProviderDefinition definition = DEFINITIONS.get(normalized);
    if (definition == null) {
      return Optional.empty();
    }
    SourceOAuthProperties.ProviderCredentials credentials = properties.credentialsOf(normalized);
    return Optional.of(
        new OAuthProvider(
            definition,
            credentials.clientId(),
            credentials.clientSecret(),
            properties.redirectUri()));
  }
}
