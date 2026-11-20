package works.momens.server.auth.internal.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** 쿠키를 실은 cross-origin 요청을 허용하려면 credentials와 명시적인 origin 목록이 필요합니다. wildcard origin은 허용하지 않습니다. */
@Validated
@ConfigurationProperties("momens.cors")
public record CorsProperties(
    List<String> allowedOrigins,
    List<String> allowedMethods,
    List<String> allowedHeaders,
    Boolean allowCredentials,
    Duration maxAge) {

  private static final List<String> DEFAULT_ALLOWED_METHODS =
      List.of("GET", "POST", "PATCH", "DELETE", "OPTIONS");
  private static final List<String> DEFAULT_ALLOWED_HEADERS =
      List.of("Content-Type", "Authorization", "API-Version");
  private static final Duration DEFAULT_MAX_AGE = Duration.ofHours(1);

  public CorsProperties {
    allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
    allowedMethods =
        (allowedMethods == null || allowedMethods.isEmpty())
            ? DEFAULT_ALLOWED_METHODS
            : List.copyOf(allowedMethods);
    allowedHeaders =
        (allowedHeaders == null || allowedHeaders.isEmpty())
            ? DEFAULT_ALLOWED_HEADERS
            : List.copyOf(allowedHeaders);
    if (allowCredentials == null) {
      allowCredentials = Boolean.TRUE;
    }
    if (maxAge == null) {
      maxAge = DEFAULT_MAX_AGE;
    }
  }
}
