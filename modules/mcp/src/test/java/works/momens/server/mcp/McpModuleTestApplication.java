package works.momens.server.mcp;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.web.servlet.config.annotation.ApiVersionConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Test bootstrap for the MCP module's JPA slice tests. */
@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan(
    excludeFilters =
        @ComponentScan.Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class))
class McpModuleTestApplication implements WebMvcConfigurer {
  @Override
  public void configureApiVersioning(ApiVersionConfigurer configurer) {
    configurer.useRequestHeader("API-Version").addSupportedVersions("1").setDefaultVersion("1");
  }
}
