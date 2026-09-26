package works.momens.server.mcp.oauth.infrastructure;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.server.authorization.web.authentication.OAuth2ErrorAuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;

/**
 * Keeps internal exception messages out of client registration error responses.
 *
 * <p>Spring Authorization Server reports parse and persistence failures as {@code invalid_request}
 * whose description is the raw exception message, which can include SQL and schema details. Those
 * descriptions stay in the server log and the client receives a generic one.
 */
@Slf4j
final class McpClientRegistrationErrorHandler implements AuthenticationFailureHandler {

  private static final String GENERIC_DESCRIPTION = "Invalid client registration request";

  private final AuthenticationFailureHandler delegate =
      new OAuth2ErrorAuthenticationFailureHandler();

  @Override
  public void onAuthenticationFailure(
      HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
      throws IOException, ServletException {
    delegate.onAuthenticationFailure(request, response, sanitize(exception));
  }

  private static AuthenticationException sanitize(AuthenticationException exception) {
    if (!(exception instanceof OAuth2AuthenticationException oauth2Exception)
        || !OAuth2ErrorCodes.INVALID_REQUEST.equals(oauth2Exception.getError().getErrorCode())) {
      return exception;
    }
    OAuth2Error error = oauth2Exception.getError();
    log.warn("mcp client registration rejected description={}", error.getDescription());
    return new OAuth2AuthenticationException(
        new OAuth2Error(error.getErrorCode(), GENERIC_DESCRIPTION, error.getUri()));
  }
}
