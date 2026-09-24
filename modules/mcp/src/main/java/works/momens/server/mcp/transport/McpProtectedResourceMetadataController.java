package works.momens.server.mcp.transport;

import jakarta.servlet.http.HttpServletRequest;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@RestController
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class McpProtectedResourceMetadataController
    implements McpProtectedResourceMetadataControllerDocs {

  private final ObjectMapper objectMapper;

  @GetMapping("/.well-known/oauth-protected-resource/mcp")
  @Override
  public ResponseEntity<JsonNode> get(HttpServletRequest request) {
    return ResponseEntity.ok(metadata(request));
  }

  private JsonNode metadata(HttpServletRequest request) {
    ObjectNode metadata = objectMapper.createObjectNode();
    metadata.put("resource", resourceUrl(request));
    return metadata;
  }

  private String resourceUrl(HttpServletRequest request) {
    StringBuilder url =
        new StringBuilder(request.getScheme()).append("://").append(request.getServerName());
    if (("http".equals(request.getScheme()) && request.getServerPort() != 80)
        || ("https".equals(request.getScheme()) && request.getServerPort() != 443)) {
      url.append(':').append(request.getServerPort());
    }
    return url.append("/api/mcp").toString();
  }
}
