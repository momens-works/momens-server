package works.momens.server.mcp.internal;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationResponseType;
import org.springframework.security.oauth2.server.authorization.OAuth2ClientRegistration;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientRegistrationAuthenticationContext;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientRegistrationAuthenticationToken;

/**
 * Accepts only public PKCE clients (ADR-0023).
 *
 * <p>Requested {@code scope} is ignored because every MCP client is registered with the full MCP
 * scope set; each authorization request narrows it.
 */
final class McpClientRegistrationValidator
    implements Consumer<OAuth2ClientRegistrationAuthenticationContext> {

  private static final int MAX_CLIENT_NAME_LENGTH = 120;
  private static final int MAX_REDIRECT_URIS = 10;
  // oauth2_registered_client.redirect_uris is varchar(1000) holding the comma-joined URIs.
  private static final int MAX_STORED_REDIRECT_URIS_LENGTH = 1000;

  private static final String INVALID_CLIENT_METADATA = "invalid_client_metadata";
  private static final String ERROR_URI =
      "https://datatracker.ietf.org/doc/html/rfc7591#section-3.2.2";
  private static final Set<String> SUPPORTED_GRANT_TYPES =
      Set.of(
          AuthorizationGrantType.AUTHORIZATION_CODE.getValue(),
          AuthorizationGrantType.REFRESH_TOKEN.getValue());
  private static final Pattern IPV4_LOOPBACK =
      Pattern.compile("^127(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}$");
  private static final Set<String> IPV6_LOOPBACK = Set.of("[::1]", "[0:0:0:0:0:0:0:1]");

  @Override
  public void accept(OAuth2ClientRegistrationAuthenticationContext context) {
    OAuth2ClientRegistrationAuthenticationToken authentication = context.getAuthentication();
    OAuth2ClientRegistration registration = authentication.getClientRegistration();
    validateClientName(registration.getClientName());
    validateRedirectUris(registration.getRedirectUris());
    validateTokenEndpointAuthMethod(registration.getTokenEndpointAuthenticationMethod());
    validateGrantTypes(registration.getGrantTypes());
    validateResponseTypes(registration.getResponseTypes());
  }

  private static void validateClientName(String clientName) {
    if (clientName == null
        || clientName.isBlank()
        || clientName.strip().length() > MAX_CLIENT_NAME_LENGTH) {
      throw invalidClientMetadata("client_name is required and must be at most 120 characters");
    }
  }

  private static void validateRedirectUris(List<String> redirectUris) {
    if (redirectUris == null || redirectUris.isEmpty() || redirectUris.size() > MAX_REDIRECT_URIS) {
      throw invalidRedirectUri("redirect_uris must contain 1 to 10 URIs");
    }
    for (String redirectUri : redirectUris) {
      // Stored comma-joined, so a comma would split one URI into several on read.
      if (redirectUri.contains(",") || !isAllowedRedirectUri(redirectUri)) {
        throw invalidRedirectUri("redirect_uri must be https or an http loopback URI");
      }
    }
    if (String.join(",", new LinkedHashSet<>(redirectUris)).length()
        > MAX_STORED_REDIRECT_URIS_LENGTH) {
      throw invalidRedirectUri("redirect_uris must be at most 1000 characters in total");
    }
  }

  private static void validateTokenEndpointAuthMethod(String method) {
    if (method != null && !ClientAuthenticationMethod.NONE.getValue().equals(method)) {
      throw invalidClientMetadata("only public PKCE clients are supported");
    }
  }

  private static void validateGrantTypes(List<String> grantTypes) {
    if (grantTypes != null && !SUPPORTED_GRANT_TYPES.containsAll(grantTypes)) {
      throw invalidClientMetadata("only authorization_code and refresh_token are supported");
    }
  }

  private static void validateResponseTypes(List<String> responseTypes) {
    if (responseTypes != null
        && responseTypes.stream()
            .anyMatch(type -> !OAuth2AuthorizationResponseType.CODE.getValue().equals(type))) {
      throw invalidClientMetadata("only the code response type is supported");
    }
  }

  private static boolean isAllowedRedirectUri(String value) {
    URI uri;
    try {
      uri = new URI(value);
    } catch (URISyntaxException exception) {
      return false;
    }
    if (!uri.isAbsolute()
        || uri.getHost() == null
        || uri.getRawUserInfo() != null
        || uri.getRawFragment() != null) {
      return false;
    }
    if ("https".equalsIgnoreCase(uri.getScheme())) {
      return true;
    }
    return "http".equalsIgnoreCase(uri.getScheme()) && isLoopbackHost(uri.getHost());
  }

  private static boolean isLoopbackHost(String host) {
    return "localhost".equalsIgnoreCase(host)
        || IPV4_LOOPBACK.matcher(host).matches()
        || IPV6_LOOPBACK.contains(host);
  }

  private static OAuth2AuthenticationException invalidClientMetadata(String description) {
    return new OAuth2AuthenticationException(
        new OAuth2Error(INVALID_CLIENT_METADATA, description, ERROR_URI));
  }

  private static OAuth2AuthenticationException invalidRedirectUri(String description) {
    return new OAuth2AuthenticationException(
        new OAuth2Error(OAuth2ErrorCodes.INVALID_REDIRECT_URI, description, ERROR_URI));
  }
}
