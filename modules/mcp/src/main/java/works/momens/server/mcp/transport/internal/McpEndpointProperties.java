package works.momens.server.mcp.transport.internal;

import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** MCP 표준 응답과 Origin 검증에 사용하는 외부 canonical 주소 설정입니다. */
@Validated
@ConfigurationProperties("momens.mcp")
public record McpEndpointProperties(@NotNull URI resourceUri, List<URI> allowedOrigins) {

  public McpEndpointProperties {
    allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
  }

  /** OAuth issuer는 resource와 같은 origin의 {@code /api}입니다(ADR-0023). */
  public URI issuerUri() {
    return URI.create(resourceUri.getScheme() + "://" + resourceUri.getAuthority() + "/api");
  }

  public URI protectedResourceMetadataUri() {
    return URI.create(
        resourceUri.getScheme()
            + "://"
            + resourceUri.getAuthority()
            + "/.well-known/oauth-protected-resource"
            + resourceUri.getPath());
  }

  public boolean allowsOrigin(String origin) {
    try {
      URI originUri = URI.create(origin);
      if (originUri.getScheme() == null
          || originUri.getAuthority() == null
          || originUri.getPath() == null
          || !originUri.getPath().isEmpty()
          || originUri.getQuery() != null
          || originUri.getFragment() != null) {
        return false;
      }
      URI resourceOrigin = URI.create(resourceUri.getScheme() + "://" + resourceUri.getAuthority());
      return resourceOrigin.equals(originUri) || allowedOrigins.contains(originUri);
    } catch (IllegalArgumentException exception) {
      return false;
    }
  }
}
