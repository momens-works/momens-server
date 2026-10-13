package works.momens.server.source.connection.oauth;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** provider 자격 증명이 비어 있으면 해당 연동만 비활성으로 판정합니다. 설정 키는 서버 네임스페이스를 쓰되 값은 레거시와 호환되어야 합니다. */
@Validated
@ConfigurationProperties(prefix = "momens.source.oauth")
public record SourceOAuthProperties(
    String redirectUri,
    String successRedirectUri,
    String stateSecret,
    String tokenKey,
    @NotNull Duration stateTtl,
    @NotNull Map<String, ProviderCredentials> providers) {

  public record ProviderCredentials(String clientId, String clientSecret) {

    public boolean isConfigured() {
      return hasText(clientId) && hasText(clientSecret);
    }
  }

  public ProviderCredentials credentialsOf(String sourceType) {
    return providers.getOrDefault(
        sourceType.toLowerCase(Locale.ROOT), new ProviderCredentials(null, null));
  }

  public boolean hasAnyConfiguredProvider() {
    return hasText(redirectUri)
        && providers.values().stream().anyMatch(ProviderCredentials::isConfigured);
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }
}
