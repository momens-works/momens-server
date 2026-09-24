package works.momens.server.mcp.transport;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import works.momens.server.mcp.transport.internal.McpEndpointProperties;

@RestController
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class McpProtectedResourceMetadataController
    implements McpProtectedResourceMetadataControllerDocs {

  private final ObjectMapper objectMapper;
  private final McpEndpointProperties endpointProperties;

  @GetMapping("/.well-known/oauth-protected-resource/api/mcp")
  @Override
  public ResponseEntity<JsonNode> get() {
    return ResponseEntity.ok(metadata());
  }

  private JsonNode metadata() {
    ObjectNode metadata = objectMapper.createObjectNode();
    metadata.put("resource", endpointProperties.resourceUri().toString());
    return metadata;
  }
}
