package works.momens.server.mcp.transport.internal;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import works.momens.server.mcp.transport.McpAuthenticationContext;

@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class McpTransportHandler {

  private final McpTransportAuthenticator authenticator;
  private final McpTransportRequestValidator requestValidator;
  private final McpTransportMethodHandler methodHandler;
  private final McpTransportResponseFactory responseFactory;

  public ResponseEntity<JsonNode> handle(JsonNode request, HttpServletRequest httpRequest) {
    Optional<McpAuthenticationContext> authentication = authenticator.authenticate(httpRequest);
    if (authentication.isEmpty()) {
      return responseFactory.unauthorized(httpRequest);
    }
    if (!requestValidator.isJsonRpcRequest(request)) {
      return responseFactory.jsonRpcError(null, -32600, "Invalid Request");
    }

    JsonNode id = request.get("id");
    String method = request.get("method").asText();
    if (!requestValidator.hasLatestProtocolHeaders(httpRequest, request, method)) {
      return responseFactory.badRequest(id, -32020, "Header mismatch");
    }
    return methodHandler.handle(method, id, authentication.get());
  }
}
