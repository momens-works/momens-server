package works.momens.server.mcp.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class McpWebPropertiesTest {
  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withUserConfiguration(McpWebConfiguration.class)
          .withPropertyValues("spring.profiles.active=local");

  @ParameterizedTest
  @ValueSource(
      strings = {
        "https://app.example.com",
        "https://app.example.com:65535",
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
        "ftp://app.example.com",
        "https://app.example.com:65536",
        "http://localhost:65536",
        "https://app.example.com:0"
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

  @ParameterizedTest
  @ValueSource(strings = {"dev", "prod", "local,prod", "test,dev", ""})
  void rejectsHttpOutsideLocalTest(String profiles) {
    runner
        .withPropertyValues(
            "spring.profiles.active=" + profiles, "momens.mcp.web-base-uri=http://localhost:5173")
        .run(context -> assertThat(context).hasFailed());
  }

  @ParameterizedTest
  @ValueSource(strings = {"local", "test"})
  void acceptsLoopbackForLocalTest(String profile) {
    runner
        .withPropertyValues(
            "spring.profiles.active=" + profile, "momens.mcp.web-base-uri=http://localhost:5173")
        .run(context -> assertThat(context).hasNotFailed());
  }

  @ParameterizedTest
  @ValueSource(strings = {"dev", "prod", ""})
  void acceptsHttpsForDeployment(String profile) {
    runner
        .withPropertyValues(
            "spring.profiles.active=" + profile, "momens.mcp.web-base-uri=https://app.example.com")
        .run(context -> assertThat(context).hasNotFailed());
  }
}
