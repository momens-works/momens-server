package works.momens.server.source.connection.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.util.UriComponentsBuilder;
import works.momens.server.common.api.BusinessException;
import works.momens.server.source.BeginInstallCommand;
import works.momens.server.source.SourceErrorCode;
import works.momens.server.source.connection.oauth.SourceOAuthProperties.ProviderCredentials;

class SourceInstallerImplTest {

  private static final UUID WORKSPACE_ID = UUID.randomUUID();
  private static final UUID USER_ID = UUID.randomUUID();
  private static final Instant NOW = Instant.parse("2026-08-21T00:00:00Z");

  private static SourceInstallerImpl installerWith(Map<String, ProviderCredentials> providers) {
    SourceOAuthProperties properties =
        new SourceOAuthProperties(
            "https://api.momens.works/api/source-connections/oauth/callback",
            null,
            "state-secret-that-is-long-enough-for-hs256",
            null,
            Duration.ofMinutes(10),
            providers);
    return new SourceInstallerImpl(
        new OAuthProviderRegistry(properties),
        new OAuthStateSigner(
            properties.stateSecret(), properties.stateTtl(), Clock.fixed(NOW, ZoneOffset.UTC)),
        null,
        TokenEncryptor.unavailable(),
        null,
        null,
        properties,
        null);
  }

  private static SourceInstallerImpl configuredInstaller() {
    return installerWith(Map.of("github", new ProviderCredentials("cid-github", "secret")));
  }

  @Test
  @DisplayName("승인 URL의 state를 검증하면 요청한 워크스페이스와 사용자와 provider가 복원된다")
  void returnsAuthorizeUrlCarryingStateThatResolvesBackToTheRequester() {
    String authorizeUrl =
        configuredInstaller()
            .beginInstall(new BeginInstallCommand(WORKSPACE_ID, USER_ID, "github"));

    String state =
        UriComponentsBuilder.fromUri(URI.create(authorizeUrl))
            .build()
            .getQueryParams()
            .getFirst("state");
    OAuthStateSigner verifier =
        new OAuthStateSigner(
            "state-secret-that-is-long-enough-for-hs256",
            Duration.ofMinutes(10),
            Clock.fixed(NOW.plusSeconds(60), ZoneOffset.UTC));

    assertThat(authorizeUrl).startsWith("https://github.com/login/oauth/authorize?");
    assertThat(verifier.verify(state)).isEqualTo(new OAuthState(WORKSPACE_ID, USER_ID, "GITHUB"));
  }

  @Test
  @DisplayName("지원하지 않는 provider는 거부한다")
  void rejectsProviderThatIsNotSupported() {
    assertThatThrownBy(
            () ->
                configuredInstaller()
                    .beginInstall(new BeginInstallCommand(WORKSPACE_ID, USER_ID, "linear")))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorCode", SourceErrorCode.SOURCE_UNSUPPORTED_PROVIDER);
  }

  @Test
  @DisplayName("자격 증명이 없는 provider는 거부한다")
  void rejectsSupportedProviderThatHasNoCredentials() {
    assertThatThrownBy(
            () ->
                installerWith(Map.of())
                    .beginInstall(new BeginInstallCommand(WORKSPACE_ID, USER_ID, "github")))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorCode", SourceErrorCode.SOURCE_PROVIDER_UNCONFIGURED);
  }

  @Test
  @DisplayName("provider 이름은 대소문자를 구분하지 않는다")
  void acceptsProviderNameInAnyLetterCase() {
    String lower =
        configuredInstaller()
            .beginInstall(new BeginInstallCommand(WORKSPACE_ID, USER_ID, "github"));
    String upper =
        configuredInstaller()
            .beginInstall(new BeginInstallCommand(WORKSPACE_ID, USER_ID, "GitHub"));

    assertThat(lower).startsWith("https://github.com/login/oauth/authorize?");
    assertThat(upper).startsWith("https://github.com/login/oauth/authorize?");
  }
}
