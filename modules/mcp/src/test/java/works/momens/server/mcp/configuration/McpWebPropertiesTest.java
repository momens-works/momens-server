package works.momens.server.mcp.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class McpWebPropertiesTest {
  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(Config.class);

  @ParameterizedTest
  @ValueSource(
      strings = {
        "https://app.example.com",
        "https://dev.example.com/",
        "http://localhost:5173",
        "http://127.0.0.1:3000",
        "http://[::1]:5173"
      })
  void acceptsWebOrigins(String uri) {
    runner
        .withPropertyValues("momens.mcp.web-base-uri=" + uri)
        .run(context -> assertThat(context).hasNotFailed());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "/relative",
        "http://app.example.com",
        "https://user@app.example.com",
        "https://app.example.com/path",
        "https://app.example.com?task=other",
        "https://app.example.com#fragment",
        "ftp://app.example.com"
      })
  void rejectsInvalidOrigins(String uri) {
    runner
        .withPropertyValues("momens.mcp.web-base-uri=" + uri)
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  void requiresExplicitOrigin() {
    runner.run(context -> assertThat(context).hasFailed());
  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(McpWebProperties.class)
  static class Config {}
}
