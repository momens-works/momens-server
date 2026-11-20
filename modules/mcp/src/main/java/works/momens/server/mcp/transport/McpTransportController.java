package works.momens.server.mcp.transport;

import jakarta.servlet.http.HttpServletRequest;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import works.momens.server.mcp.transport.internal.McpTransportHandler;

@RestController
@RequestMapping("/api/mcp")
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class McpTransportController implements McpTransportControllerDocs {

  private final McpTransportHandler transportHandler;

  @PostMapping(
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Override
  public ResponseEntity<JsonNode> post(
      @RequestBody String request, HttpServletRequest httpServletRequest) {
    return transportHandler.handle(request, httpServletRequest);
  }
}
