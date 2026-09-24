package works.momens.server.mcp.transport.internal;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import works.momens.server.mcp.transport.McpAuthenticationContext;
import works.momens.server.mcp.transport.McpBearerTokenVerifier;
import works.momens.server.mcp.transport.McpToolCatalog;
import works.momens.server.mcp.transport.McpToolDefinition;
import works.momens.server.mcp.transport.McpTransportController;

@WebMvcTest(McpTransportController.class)
@Import({
  McpTransportHandler.class,
  McpTransportRequestValidator.class,
  McpTransportMethodHandler.class,
  McpTransportResponseFactory.class,
  McpTransportAuthenticator.class
})
class McpTransportControllerTest {

  private static final McpAuthenticationContext AUTHENTICATION_CONTEXT =
      new McpAuthenticationContext(
          UUID.fromString("00000000-0000-0000-0000-000000000001"),
          "user-1",
          Set.of("mcp:projects:read"));

  @Autowired private MockMvc mockMvc;

  @MockitoBean private McpBearerTokenVerifier bearerTokenVerifier;

  @MockitoBean private McpToolCatalog toolCatalog;

  @Test
  void rejectsUnauthenticatedRequestWithResourceMetadataHint() throws Exception {
    mockMvc
        .perform(
            post("/api/mcp")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(
            header()
                .string(
                    "WWW-Authenticate",
                    "Bearer resource_metadata=\"http://localhost/.well-known/oauth-protected-resource/mcp\""))
        .andExpect(content().string(""));
  }

  @Test
  void discoversProtocolAndCapabilitiesWithoutSession() throws Exception {
    when(bearerTokenVerifier.verify("token")).thenReturn(Optional.of(AUTHENTICATION_CONTEXT));

    mockMvc
        .perform(
            post("/api/mcp")
                .header("Authorization", "Bearer token")
                .header("MCP-Protocol-Version", "2026-07-28")
                .header("Mcp-Method", "server/discover")
                .contentType(MediaType.APPLICATION_JSON)
                .content(discoverRequest()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.jsonrpc").value("2.0"))
        .andExpect(jsonPath("$.id").value(1))
        .andExpect(jsonPath("$.result.resultType").value("complete"))
        .andExpect(jsonPath("$.result.supportedVersions[0]").value("2026-07-28"))
        .andExpect(jsonPath("$.result.capabilities.tools.listChanged").value(false))
        .andExpect(
            jsonPath("$.result._meta['io.modelcontextprotocol/serverInfo'].name")
                .value("momens-mcp"))
        .andExpect(jsonPath("$.result.ttlMs").value(3600000))
        .andExpect(jsonPath("$.result.cacheScope").value("public"));
  }

  @Test
  void listsDeterministicToolsWithVerifiedAuthenticationContext() throws Exception {
    when(bearerTokenVerifier.verify("token")).thenReturn(Optional.of(AUTHENTICATION_CONTEXT));
    when(toolCatalog.list(AUTHENTICATION_CONTEXT))
        .thenReturn(List.of(new McpToolDefinition("list_projects", "List projects", schema())));

    mockMvc
        .perform(
            post("/api/mcp")
                .header("Authorization", "Bearer token")
                .header("MCP-Protocol-Version", "2026-07-28")
                .header("Mcp-Method", "tools/list")
                .contentType(MediaType.APPLICATION_JSON)
                .content(toolsListRequest()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.result.resultType").value("complete"))
        .andExpect(jsonPath("$.result.tools[0].name").value("list_projects"))
        .andExpect(jsonPath("$.result.tools[0].inputSchema.type").value("object"))
        .andExpect(jsonPath("$.result.ttlMs").value(300000))
        .andExpect(jsonPath("$.result.cacheScope").value("private"));

    verify(toolCatalog).list(eq(AUTHENTICATION_CONTEXT));
  }

  @Test
  void mapsUnknownMethodToJsonRpcError() throws Exception {
    when(bearerTokenVerifier.verify("token")).thenReturn(Optional.of(AUTHENTICATION_CONTEXT));

    mockMvc
        .perform(
            post("/api/mcp")
                .header("Authorization", "bEaReR token")
                .header("MCP-Protocol-Version", "2026-07-28")
                .header("Mcp-Method", "unknown")
                .contentType(MediaType.APPLICATION_JSON)
                .content(request("unknown", 3)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value(-32601))
        .andExpect(jsonPath("$.error.message").value("Method not found"));
  }

  @Test
  void rejectsMissingLatestProtocolHeaders() throws Exception {
    when(bearerTokenVerifier.verify("token")).thenReturn(Optional.of(AUTHENTICATION_CONTEXT));

    mockMvc
        .perform(
            post("/api/mcp")
                .header("Authorization", "Bearer token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(discoverRequest()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value(-32020));
  }

  @Test
  void acceptsDiscoveryWithoutClientInfoOrName() throws Exception {
    when(bearerTokenVerifier.verify("token")).thenReturn(Optional.of(AUTHENTICATION_CONTEXT));

    mockMvc
        .perform(
            post("/api/mcp")
                .header("Authorization", "Bearer token")
                .header("MCP-Protocol-Version", "2026-07-28")
                .header("Mcp-Method", "server/discover")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestWithoutClientInfo("server/discover", 4)))
        .andExpect(status().isOk());
  }

  @Test
  void decodesBase64SentinelMcpNameBeforeValidation() throws Exception {
    when(bearerTokenVerifier.verify("token")).thenReturn(Optional.of(AUTHENTICATION_CONTEXT));

    mockMvc
        .perform(
            post("/api/mcp")
                .header("Authorization", "Bearer token")
                .header("MCP-Protocol-Version", "2026-07-28")
                .header("Mcp-Method", "tools/call")
                .header("Mcp-Name", "=?base64?Z2V0X3dlYXRoZXI=?=")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestWithName("tools/call", 5, "get_weather")))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value(-32601));
  }

  private static String discoverRequest() {
    return request("server/discover", 1);
  }

  private static String toolsListRequest() {
    return request("tools/list", 2);
  }

  private static String request(String method, int id) {
    return requestWithMetadata(
        method,
        id,
        "\"io.modelcontextprotocol/clientInfo\":{"
            + "\"name\":\"test-client\",\"version\":\"1.0\"},");
  }

  private static String requestWithName(String method, int id, String name) {
    return "{\"jsonrpc\":\"2.0\",\"id\":"
        + id
        + ",\"method\":\""
        + method
        + "\",\"params\":{\"name\":\""
        + name
        + "\",\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\",\"io.modelcontextprotocol/clientCapabilities\":{}}}}";
  }

  private static String requestWithoutClientInfo(String method, int id) {
    return requestWithMetadata(method, id, "");
  }

  private static String requestWithMetadata(String method, int id, String clientInfo) {
    return "{\"jsonrpc\":\"2.0\",\"id\":"
        + id
        + ",\"method\":\""
        + method
        + "\",\"params\":{\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\","
        + clientInfo
        + "\"io.modelcontextprotocol/clientCapabilities\":{}}}}";
  }

  private static tools.jackson.databind.JsonNode schema() {
    return new tools.jackson.databind.ObjectMapper().createObjectNode().put("type", "object");
  }
}
