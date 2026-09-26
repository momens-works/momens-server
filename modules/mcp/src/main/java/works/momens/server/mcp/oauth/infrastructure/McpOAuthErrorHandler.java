package works.momens.server.mcp.oauth.infrastructure;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.http.converter.OAuth2ErrorHttpMessageConverter;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

/** OAuth errors never expose SQL, framework messages, or unvalidated redirects. */
@RequiredArgsConstructor
final class McpOAuthErrorHandler implements AuthenticationFailureHandler {
  private final RegisteredClientRepository clients;

  @Override
  public void onAuthenticationFailure(
      HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
      throws IOException {
    String code =
        exception instanceof OAuth2AuthenticationException oauth
            ? oauth.getError().getErrorCode()
            : "server_error";
    if (McpAuthorizationServerConfig.AUTHORIZATION_ENDPOINT.equals(request.getRequestURI())) {
      String clientId = singleParameter(request, "client_id");
      String redirect = singleParameter(request, "redirect_uri");
      RegisteredClient client = clientId == null ? null : clients.findByClientId(clientId);
      if (client != null && redirect != null && client.getRedirectUris().contains(redirect)) {
        UriComponentsBuilder builder =
            UriComponentsBuilder.fromUriString(redirect).replaceQueryParam("error", code);
        String state = singleParameter(request, "state");
        if (state != null) {
          builder.replaceQueryParam("state", UriUtils.encode(state, StandardCharsets.UTF_8));
        }
        response.sendRedirect(builder.build(true).toUriString());
        return;
      }
    }
    response.setStatus(
        "server_error".equals(code) ? 500 : "invalid_client".equals(code) ? 401 : 400);
    response.setHeader("Cache-Control", "no-store");
    response.setHeader("Pragma", "no-cache");
    new OAuth2ErrorHttpMessageConverter()
        .write(
            new OAuth2Error(code, "The OAuth request could not be processed", null),
            null,
            new ServletServerHttpResponse(response));
  }

  private static String singleParameter(HttpServletRequest request, String name) {
    String[] values = request.getParameterValues(name);
    return values != null && values.length == 1 ? values[0] : null;
  }
}
