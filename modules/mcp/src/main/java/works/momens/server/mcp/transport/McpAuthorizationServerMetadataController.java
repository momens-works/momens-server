package works.momens.server.mcp.transport;

import java.net.URI;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import works.momens.server.mcp.grant.McpScope;

/**
 * RFC 8414 metadata for the {@code /api} issuer. The path-inserted well-known URI is served here
 * because Spring Authorization Server's single-issuer metadata endpoint only answers at the root.
 */
@RestController
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class McpAuthorizationServerMetadataController
    implements McpAuthorizationServerMetadataControllerDocs {

  private final ObjectMapper objectMapper;
  private final AuthorizationServerSettings authorizationServerSettings;

  @GetMapping("/.well-known/oauth-authorization-server/api")
  @Override
  public ResponseEntity<JsonNode> get() {
    return ResponseEntity.ok(metadata());
  }

  private JsonNode metadata() {
    URI issuer = URI.create(authorizationServerSettings.getIssuer());
    String origin = issuer.getScheme() + "://" + issuer.getAuthority();
    ObjectNode metadata = objectMapper.createObjectNode();
    metadata.put("issuer", issuer.toString());
    metadata.put(
        "authorization_endpoint", origin + authorizationServerSettings.getAuthorizationEndpoint());
    metadata.put("token_endpoint", origin + authorizationServerSettings.getTokenEndpoint());
    metadata.put(
        "registration_endpoint",
        origin + authorizationServerSettings.getClientRegistrationEndpoint());
    metadata.put(
        "revocation_endpoint", origin + authorizationServerSettings.getTokenRevocationEndpoint());
    metadata.putArray("response_types_supported").add("code");
    metadata.putArray("grant_types_supported").add("authorization_code").add("refresh_token");
    metadata.putArray("code_challenge_methods_supported").add("S256");
    metadata.putArray("token_endpoint_auth_methods_supported").add("none");
    metadata.putArray("revocation_endpoint_auth_methods_supported").add("none");
    ArrayNode scopes = metadata.putArray("scopes_supported");
    for (McpScope scope : McpScope.values()) {
      scopes.add(scope.value());
    }
    return metadata;
  }
}
