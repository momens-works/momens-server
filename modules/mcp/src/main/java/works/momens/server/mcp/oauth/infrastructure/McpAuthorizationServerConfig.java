package works.momens.server.mcp.oauth.infrastructure;

import java.time.Clock;
import java.util.function.UnaryOperator;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.authorization.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationConsentAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientRegistrationAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import works.momens.server.mcp.configuration.McpEndpointProperties;
import works.momens.server.mcp.configuration.McpOAuthProperties;
import works.momens.server.mcp.grant.McpGrantReader;
import works.momens.server.mcp.oauth.application.McpInteractionService;
import works.momens.server.mcp.oauth.application.McpTokenService;
import works.momens.server.workspace.membership.WorkspaceMembershipReader;

/**
 * MCP OAuth authorization server built on Spring Authorization Server.
 *
 * <p>The issuer is {@code <origin>/api} (ADR-0023). Spring Authorization Server serves endpoints at
 * the configured request paths, so they carry the {@code /api} prefix themselves.
 */
@Configuration
@EnableConfigurationProperties(McpOAuthProperties.class)
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
  SecurityFilterChain mcpAuthorizationServerSecurityFilterChain(
      HttpSecurity http,
      Clock clock,
      RegisteredClientRepository clients,
      OAuth2AuthorizationService authorizations,
      McpInteractionService interactions,
      McpTokenFamilyStore families,
      McpGrantReader grants,
      WorkspaceMembershipReader memberships,
      PlatformTransactionManager transactionManager,
      McpEndpointProperties endpoints)
      throws Exception {
    OAuth2AuthorizationServerConfigurer authorizationServer =
        new OAuth2AuthorizationServerConfigurer();
    McpPublicClientAuthentication publicClients = new McpPublicClientAuthentication(clients);
    McpOAuthErrorHandler errors = new McpOAuthErrorHandler(clients);
    TransactionTemplate transactions = new TransactionTemplate(transactionManager);
    McpTokenService tokens =
        new McpTokenService(
            authorizations,
            families,
            grants,
            memberships,
            transactions,
            endpoints.resourceUri().toString());
    UnaryOperator<AuthenticationProvider> tokenProvider =
        provider -> new McpTokenAuthenticationProvider(provider, tokens);
    // SAS 7.1 reads the original provider's validator during initialization. Replace the
    // registered provider after that customization, preserving its pre-validation setup.
    authorizationServer.withObjectPostProcessor(
        new ObjectPostProcessor<AuthenticationProvider>() {
          @Override
          @SuppressWarnings("unchecked")
          public <O extends AuthenticationProvider> O postProcess(O provider) {
            if (provider instanceof OAuth2AuthorizationCodeRequestAuthenticationProvider) {
              return (O)
                  new McpAuthorizationRequestProvider(
                      interactions, clients, endpoints.resourceUri().toString());
            }
            return provider;
          }
        });
    return http.securityMatcher(
            CLIENT_REGISTRATION_ENDPOINT,
            AUTHORIZATION_ENDPOINT,
            TOKEN_ENDPOINT,
            TOKEN_REVOCATION_ENDPOINT)
        .cors(Customizer.withDefaults())
        .with(
            authorizationServer,
            server ->
                server
                    .tokenGenerator(new McpTokenGenerator(clock))
                    .clientAuthentication(
                        client ->
                            client
                                .authenticationConverter(publicClients)
                                .authenticationProvider(publicClients)
                                .errorResponseHandler(errors))
                    .authorizationEndpoint(
                        endpoint ->
                            endpoint
                                .errorResponseHandler(errors)
                                .authenticationProviders(
                                    providers -> {
                                      providers.removeIf(
                                          provider ->
                                              provider
                                                  instanceof
                                                  OAuth2AuthorizationConsentAuthenticationProvider);
                                      providers.forEach(
                                          provider -> {
                                            if (provider
                                                instanceof
                                                OAuth2AuthorizationCodeRequestAuthenticationProvider
                                                    authorization) {
                                              authorization.setAuthenticationValidator(
                                                  new McpAuthorizationRequestValidator(
                                                      endpoints.resourceUri().toString()));
                                            }
                                          });
                                    })
                                .authorizationResponseHandler(
                                    (request, response, authentication) ->
                                        response.sendRedirect(
                                            (String) authentication.getDetails())))
                    .tokenEndpoint(
                        endpoint ->
                            endpoint
                                .errorResponseHandler(errors)
                                .accessTokenRequestConverter(
                                    request -> {
                                      String grant = request.getParameter("grant_type");
                                      if (!"authorization_code".equals(grant)
                                          && !"refresh_token".equals(grant)) {
                                        throw new OAuth2AuthenticationException(
                                            "unsupported_grant_type");
                                      }
                                      return null;
                                    })
                                .authenticationProviders(
                                    providers -> providers.replaceAll(tokenProvider)))
                    .tokenRevocationEndpoint(
                        endpoint ->
                            endpoint
                                .errorResponseHandler(errors)
                                .authenticationProviders(
                                    providers -> providers.replaceAll(tokenProvider)))
                    .clientRegistrationEndpoint(
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
