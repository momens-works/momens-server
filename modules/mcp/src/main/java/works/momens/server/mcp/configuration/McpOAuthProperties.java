package works.momens.server.mcp.configuration;

import jakarta.validation.constraints.AssertTrue;
import java.net.URI;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("momens.mcp.oauth")
public record McpOAuthProperties(URI consentUri) {
  @AssertTrue(message = "consent-uri must be HTTPS or HTTP loopback without userinfo or fragment")
  public boolean isConsentUriValid() {
    if (consentUri == null || consentUri.toString().isEmpty()) {
      return true;
    }
    return consentUri.getHost() != null
        && consentUri.getUserInfo() == null
        && consentUri.getFragment() == null
        && ("https".equals(consentUri.getScheme())
            || ("http".equals(consentUri.getScheme())
                && Set.of("localhost", "127.0.0.1", "[::1]").contains(consentUri.getHost())));
  }
}
