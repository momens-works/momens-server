package works.momens.server.mcp.transport.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import works.momens.server.mcp.configuration.McpEndpointProperties;
import works.momens.server.mcp.transport.McpAuthenticationContext;
import works.momens.server.mcp.transport.McpBearerTokenVerifier;
import works.momens.server.mcp.transport.McpProtectedResourceMetadataController;
import works.momens.server.mcp.transport.McpToolCatalog;
import works.momens.server.mcp.transport.McpToolDefinition;
import works.momens.server.mcp.transport.McpToolExecutor;
import works.momens.server.mcp.transport.McpTransportController;

@WebMvcTest({McpTransportController.class, McpProtectedResourceMetadataController.class})
@Import({
  McpTransportHandler.class,
  McpTransportRequestValidator.class,
  McpTransportMethodHandler.class,
  McpTransportResponseFactory.class,
  McpTransportAuthenticator.class,
  McpTransportControllerTest.TestConfig.class
})
class McpTransportControllerTest {

  private static final McpAuthenticationContext AUTHENTICATION_CONTEXT =
      new McpAuthenticationContext(
          UUID.fromString("00000000-0000-0000-0000-000000000001"),
          UUID.fromString("00000000-0000-0000-0000-000000000002"),
          "client-1",
          UUID.fromString("00000000-0000-0000-0000-000000000003"),
          Set.of("mcp:projects:read"));

  @Autowired private MockMvc mockMvc;

  @MockitoBean private McpBearerTokenVerifier bearerTokenVerifier;

  @MockitoBean private McpToolCatalog toolCatalog;

  @MockitoBean private McpToolExecutor toolExecutor;

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
                    "Bearer resource_metadata=\"https://api.momens.works/.well-known/oauth-protected-resource/api/mcp\""))
        .andExpect(content().string(""));
  }

  @Test
  void exposesProtectedResourceMetadata() throws Exception {
    mockMvc
        .perform(get("/.well-known/oauth-protected-resource/api/mcp"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.resource").value("https://api.momens.works/api/mcp"))
        .andExpect(jsonPath("$.authorization_servers[0]").value("https://api.momens.works/api"))
        .andExpect(jsonPath("$.scopes_supported[0]").value("mcp:projects:read"))
        .andExpect(jsonPath("$.bearer_methods_supported[0]").value("header"));
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
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
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
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                .contentType(MediaType.APPLICATION_JSON)
                .content(toolsListRequest()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.result.resultType").value("complete"))
        .andExpect(jsonPath("$.result.tools[0].name").value("list_projects"))
        .andExpect(jsonPath("$.result.tools[0].inputSchema.type").value("object"))
        .andExpect(jsonPath("$.result.ttlMs").value(300000))
        .andExpect(jsonPath("$.result.cacheScope").value("private"))
        .andExpect(
            jsonPath("$.result._meta['io.modelcontextprotocol/serverInfo'].name")
                .value("momens-mcp"));

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
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
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
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                .contentType(MediaType.APPLICATION_JSON)
                .content(discoverRequest()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value(-32020));
  }

  @Test
  void rejectsRequestWithoutBothRequiredAcceptTypes() throws Exception {
    when(bearerTokenVerifier.verify("token")).thenReturn(Optional.of(AUTHENTICATION_CONTEXT));

    mockMvc
        .perform(
            post("/api/mcp")
                .header("Authorization", "Bearer token")
                .header("MCP-Protocol-Version", "2026-07-28")
                .header("Mcp-Method", "server/discover")
                .accept(MediaType.APPLICATION_JSON)
                .contentType(MediaType.APPLICATION_JSON)
                .content(discoverRequest()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value(-32020));
  }

  @Test
  void rejectsUnsupportedProtocolVersionWithSupportedVersions() throws Exception {
    when(bearerTokenVerifier.verify("token")).thenReturn(Optional.of(AUTHENTICATION_CONTEXT));
    String request = discoverRequest().replace("2026-07-28", "2025-03-26");

    mockMvc
        .perform(
            post("/api/mcp")
                .header("Authorization", "Bearer token")
                .header("MCP-Protocol-Version", "2025-03-26")
                .header("Mcp-Method", "server/discover")
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                .contentType(MediaType.APPLICATION_JSON)
                .content(request))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value(-32022))
        .andExpect(jsonPath("$.error.data.supported[0]").value("2026-07-28"))
        .andExpect(jsonPath("$.error.data.requested").value("2025-03-26"));
  }

  @Test
  void mapsMalformedJsonToJsonRpcParseError() throws Exception {
    when(bearerTokenVerifier.verify("token")).thenReturn(Optional.of(AUTHENTICATION_CONTEXT));

    mockMvc
        .perform(
            post("/api/mcp")
                .header("Authorization", "Bearer token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{not-json"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.jsonrpc").value("2.0"))
        .andExpect(jsonPath("$.id").doesNotExist())
        .andExpect(jsonPath("$.error.code").value(-32700));
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
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestWithoutClientInfo("server/discover", 4)))
        .andExpect(status().isOk());
  }

  @Test
  void rejectsNonIntegralJsonRpcId() throws Exception {
    when(bearerTokenVerifier.verify("token")).thenReturn(Optional.of(AUTHENTICATION_CONTEXT));

    mockMvc
        .perform(
            post("/api/mcp")
                .header("Authorization", "Bearer token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestWithId("server/discover", "1.5")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value(-32600));
  }

  @Test
  void rejectsNotificationWithBadRequest() throws Exception {
    when(bearerTokenVerifier.verify("token")).thenReturn(Optional.of(AUTHENTICATION_CONTEXT));

    mockMvc
        .perform(
            post("/api/mcp")
                .header("Authorization", "Bearer token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.id").doesNotExist())
        .andExpect(jsonPath("$.error.code").value(-32600));
  }

  @Test
  void rejectsInvalidToolDefinition() {
    assertThrows(
        IllegalArgumentException.class, () -> new McpToolDefinition(" ", "description", schema()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new McpToolDefinition("tool", "description", new ObjectMapper().createArrayNode()));
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
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestWithName("tools/call", 5, "get_weather")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.error.code").value(-32602));
  }

  @Test
  void dispatchesToolCallWithArgumentsAndVerifiedContext() throws Exception {
    when(bearerTokenVerifier.verify("token")).thenReturn(Optional.of(AUTHENTICATION_CONTEXT));
    ObjectMapper mapper = new ObjectMapper();
    JsonNode result =
        mapper.readTree(
            "{\"resultType\":\"complete\",\"content\":[{\"type\":\"text\",\"text\":\"Projects\"}]}");
    when(toolExecutor.call(
            eq("list_projects"), eq(mapper.createObjectNode()), eq(AUTHENTICATION_CONTEXT)))
        .thenReturn(Optional.of(result));
    mockMvc
        .perform(
            post("/api/mcp")
                .header("Authorization", "Bearer token")
                .header("MCP-Protocol-Version", "2026-07-28")
                .header("Mcp-Method", "tools/call")
                .header("Mcp-Name", "list_projects")
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestWithName("tools/call", 10, "list_projects")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(10))
        .andExpect(jsonPath("$.result.content[0].text").value("Projects"));
    verify(toolExecutor).call("list_projects", mapper.createObjectNode(), AUTHENTICATION_CONTEXT);
  }

  @ParameterizedTest
  @ValueSource(strings = {"list_projects", "unregistered-private-input"})
  @ExtendWith(OutputCaptureExtension.class)
  void hidesUnexpectedToolFailureDetails(String toolName, CapturedOutput output) throws Exception {
    when(toolCatalog.list(AUTHENTICATION_CONTEXT))
        .thenReturn(List.of(new McpToolDefinition("list_projects", "List projects", schema())));
    when(bearerTokenVerifier.verify("token")).thenReturn(Optional.of(AUTHENTICATION_CONTEXT));
    when(toolExecutor.call(anyString(), any(), any()))
        .thenThrow(new IllegalStateException("SQL secret"));
    mockMvc
        .perform(
            post("/api/mcp")
                .header("Authorization", "Bearer token")
                .header("MCP-Protocol-Version", "2026-07-28")
                .header("Mcp-Method", "tools/call")
                .header("Mcp-Name", toolName)
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestWithName("tools/call", 10, toolName)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.error.code").value(-32603))
        .andExpect(jsonPath("$.error.message").value("Internal error"));
    assertThat(output.getAll())
        .contains(
            "MCP tool execution failed tool="
                + (toolName.equals("list_projects") ? "list_projects" : "unknown"))
        .doesNotContain("SQL secret", "unregistered-private-input");
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

  private static String requestWithId(String method, String id) {
    return requestWithMetadata(method, id, "");
  }

  private static String requestWithMetadata(String method, int id, String clientInfo) {
    return requestWithMetadata(method, Integer.toString(id), clientInfo);
  }

  private static String requestWithMetadata(String method, String id, String clientInfo) {
    return "{\"jsonrpc\":\"2.0\",\"id\":"
        + id
        + ",\"method\":\""
        + method
        + "\",\"params\":{\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\","
        + clientInfo
        + "\"io.modelcontextprotocol/clientCapabilities\":{}}}}";
  }

  private static JsonNode schema() {
    return new ObjectMapper().createObjectNode().put("type", "object");
  }

  @TestConfiguration
  static class TestConfig {

    @Bean
    McpEndpointProperties mcpEndpointProperties() {
      return new McpEndpointProperties(URI.create("https://api.momens.works/api/mcp"), List.of());
    }
  }
}
