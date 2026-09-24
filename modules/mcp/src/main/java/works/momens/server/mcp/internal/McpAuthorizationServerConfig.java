package works.momens.server.mcp.internal;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.authorization.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientRegistrationAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.web.SecurityFilterChain;
import works.momens.server.mcp.transport.internal.McpEndpointProperties;

/**
 * MCP OAuth authorization server built on Spring Authorization Server.
 *
 * <p>The issuer is {@code <origin>/api} (ADR-0023). Spring Authorization Server serves endpoints at
 * the configured request paths, so they carry the {@code /api} prefix themselves.
 */
@Configuration
class McpAuthorizationServerConfig {

  static final String AUTHORIZATION_ENDPOINT = "/api/oauth2/authorize";
  static final String TOKEN_ENDPOINT = "/api/oauth2/token";
  static final String TOKEN_REVOCATION_ENDPOINT = "/api/oauth2/revoke";
  static final String CLIENT_REGISTRATION_ENDPOINT = "/api/oauth2/register";

  @Bean
  AuthorizationServerSettings authorizationServerSettings(
      McpEndpointProperties endpointProperties) {
    return AuthorizationServerSettings.builder()
        .issuer(endpointProperties.issuerUri().toString())
        .authorizationEndpoint(AUTHORIZATION_ENDPOINT)
        .tokenEndpoint(TOKEN_ENDPOINT)
        .tokenRevocationEndpoint(TOKEN_REVOCATION_ENDPOINT)
        .clientRegistrationEndpoint(CLIENT_REGISTRATION_ENDPOINT)
        .build();
  }

  @Bean
  @Order(0)
  SecurityFilterChain mcpAuthorizationServerSecurityFilterChain(HttpSecurity http, Clock clock)
      throws Exception {
    OAuth2AuthorizationServerConfigurer authorizationServer =
        new OAuth2AuthorizationServerConfigurer();
    return http.securityMatcher(CLIENT_REGISTRATION_ENDPOINT)
        .with(
            authorizationServer,
            server ->
                server.clientRegistrationEndpoint(
                    registration ->
                        registration
                            .openRegistrationAllowed(true)
                            .errorResponseHandler(new McpClientRegistrationErrorHandler())
                            .authenticationProviders(
                                providers ->
                                    providers.forEach(
                                        provider -> customizeRegistration(provider, clock)))))
        // MCP clients register openly without an initial access token. Ignoring bearer tokens
        // keeps Momens user tokens out of this chain's resource-server authentication.
        .oauth2ResourceServer(resourceServer -> resourceServer.bearerTokenResolver(request -> null))
        .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
        .build();
  }

  private static void customizeRegistration(Object provider, Clock clock) {
    if (provider instanceof OAuth2ClientRegistrationAuthenticationProvider registrationProvider) {
      registrationProvider.setAuthenticationValidator(new McpClientRegistrationValidator());
      registrationProvider.setRegisteredClientConverter(new McpRegisteredClientConverter(clock));
    }
  }
}
