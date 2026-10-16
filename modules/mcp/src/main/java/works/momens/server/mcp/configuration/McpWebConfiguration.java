package works.momens.server.mcp.configuration;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.util.Assert;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(McpWebProperties.class)
public class McpWebConfiguration {
  public McpWebConfiguration(McpWebProperties properties, Environment environment) {
    boolean localHttpAllowed =
        environment.acceptsProfiles(Profiles.of("local", "test"))
            && !environment.acceptsProfiles(Profiles.of("dev", "prod"));
    Assert.state(
        localHttpAllowed || "https".equals(properties.webBaseUri().getScheme()),
        "momens.mcp.web-base-uri requires HTTPS outside local/test profiles");
  }
}
