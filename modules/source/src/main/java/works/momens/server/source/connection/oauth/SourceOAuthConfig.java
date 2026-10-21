package works.momens.server.source.connection.oauth;

import java.time.Clock;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * source OAuth 연동에 필요한 구성 요소를 등록합니다.
 *
 * <p><strong>설정이 비어 있어도 서버는 정상적으로 기동되어야 합니다.</strong> 운영 환경에 provider 자격 증명을 주입하기 전에도 다른 기능은 동작해야
 * 하기 때문입니다. provider가 하나도 설정되지 않은 경우에는 state 서명과 토큰 암호화를 사용할 수 없는 상태로 구성하고, 실제 연결 흐름에서 해당 기능을 사용하려
 * 할 때 실패시킵니다. 레거시도 provider 자격 증명이 있을 때만 토큰 암호화 키를 읽습니다.
 *
 * <p>시각을 제공하는 {@link Clock}은 별도 구성 요소로 등록하지 않습니다. {@code :auth} 모듈에서도 같은 타입을 주입받으므로 {@code Clock}
 * 빈을 추가하면 인증 기능이 동작하지 않습니다.
 */
@Configuration
@EnableConfigurationProperties({
  SourceOAuthProperties.class,
  FigmaWebhookProperties.class,
  FigmaTokenRefreshProperties.class
})
class SourceOAuthConfig {

  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
  private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

  @Bean
  OAuthProviderRegistry oauthProviderRegistry(SourceOAuthProperties properties) {
    return new OAuthProviderRegistry(properties);
  }

  @Bean
  OAuthStateSigner oauthStateSigner(SourceOAuthProperties properties) {
    return properties.hasAnyConfiguredProvider()
        ? new OAuthStateSigner(properties.stateSecret(), properties.stateTtl(), Clock.systemUTC())
        : OAuthStateSigner.unavailable();
  }

  @Bean
  TokenEncryptor sourceTokenEncryptor(SourceOAuthProperties properties) {
    return properties.hasAnyConfiguredProvider()
        ? new TokenEncryptor(properties.tokenKey())
        : TokenEncryptor.unavailable();
  }

  /**
   * provider 호출에는 유한한 timeout을 명시합니다. 승인 결과를 받는 경로가 이 호출을 동기로 기다리므로, provider가 응답하지 않으면 요청 스레드가 무기한
   * 묶입니다. 레거시는 timeout 없는 기본 클라이언트를 쓰지만 그대로 따르지 않습니다.
   */
  @Bean
  ProviderOAuthClient providerOAuthClient() {
    SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout(CONNECT_TIMEOUT);
    requestFactory.setReadTimeout(READ_TIMEOUT);
    return new ProviderOAuthClient(RestClient.builder().requestFactory(requestFactory).build());
  }

  @Bean
  FigmaWebhookClient figmaWebhookClient() {
    SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout(CONNECT_TIMEOUT);
    requestFactory.setReadTimeout(READ_TIMEOUT);
    return new FigmaWebhookClient(
        RestClient.builder()
            .baseUrl("https://api.figma.com")
            .requestFactory(requestFactory)
            .build());
  }

  @Bean
  FigmaTokenRefreshClient figmaTokenRefreshClient() {
    SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout(CONNECT_TIMEOUT);
    requestFactory.setReadTimeout(READ_TIMEOUT);
    return new FigmaTokenRefreshClient(
        RestClient.builder()
            .baseUrl("https://api.figma.com")
            .requestFactory(requestFactory)
            .build());
  }
}
