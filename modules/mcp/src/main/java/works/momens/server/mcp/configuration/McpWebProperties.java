package works.momens.server.mcp.configuration;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("momens.mcp")
public record McpWebProperties(@NotNull URI webBaseUri) {
  @AssertTrue(message = "web-base-uri must be an HTTPS origin or HTTP loopback origin")
  public boolean isWebBaseUriValid() {
    return webBaseUri != null
        && webBaseUri.getHost() != null
        && webBaseUri.getUserInfo() == null
        && webBaseUri.getQuery() == null
        && webBaseUri.getFragment() == null
        && Set.of("", "/").contains(webBaseUri.getPath())
        && ("https".equals(webBaseUri.getScheme())
            || ("http".equals(webBaseUri.getScheme())
                && Set.of("localhost", "127.0.0.1", "[::1]").contains(webBaseUri.getHost())));
  }
}
