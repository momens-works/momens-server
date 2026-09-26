package works.momens.server.mcp.transport.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import works.momens.server.mcp.configuration.McpEndpointProperties;

class McpOriginValidationFilterTest {

  private final McpOriginValidationFilter filter =
      new McpOriginValidationFilter(
          new McpEndpointProperties(
              URI.create("https://api.momens.works/api/mcp"),
              List.of(URI.create("https://app.momens.works"))));

  @Test
  void rejectsUntrustedOriginOnTransportEndpoint() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/mcp");
    request.addHeader("Origin", "https://attacker.example");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, new MockFilterChain());

    assertThat(response.getStatus()).isEqualTo(403);
  }

  @Test
  void acceptsConfiguredAndMissingOrigins() throws Exception {
    MockHttpServletRequest configured = new MockHttpServletRequest("POST", "/api/mcp");
    configured.addHeader("Origin", "https://app.momens.works");
    MockHttpServletResponse configuredResponse = new MockHttpServletResponse();
    filter.doFilter(configured, configuredResponse, new MockFilterChain());

    MockHttpServletRequest absent = new MockHttpServletRequest("POST", "/api/mcp");
    MockHttpServletResponse absentResponse = new MockHttpServletResponse();
    filter.doFilter(absent, absentResponse, new MockFilterChain());

    assertThat(configuredResponse.getStatus()).isEqualTo(200);
    assertThat(absentResponse.getStatus()).isEqualTo(200);
  }
}
