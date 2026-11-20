package works.momens.server.workspace.email;

import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** provider 미지정 시 발송하지 않는 구현을 등록합니다. Resend를 지정하면 누락 설정을 기동 시점에 거부합니다. */
@Configuration
@EnableConfigurationProperties(InvitationEmailProperties.class)
class InvitationEmailConfig {

  private static final String PROVIDER_PROPERTY = "momens.workspace.invitation.email.provider";
  private static final String RESEND_ENDPOINT = "https://api.resend.com/emails";
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
  private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

  @Bean
  InvitationEmailSender invitationEmailSender(
      EmailClient emailClient, InvitationEmailProperties properties) {
    return new InvitationEmailSenderImpl(emailClient, properties);
  }

  @Bean
  @ConditionalOnProperty(name = PROVIDER_PROPERTY, havingValue = "noop", matchIfMissing = true)
  EmailClient noopEmailClient() {
    return new NoopEmailClient();
  }

  @Configuration
  @ConditionalOnProperty(name = PROVIDER_PROPERTY, havingValue = "resend")
  @EnableConfigurationProperties(ResendEmailProperties.class)
  static class ResendEmailConfig {

    @Bean
    EmailClient resendEmailClient(
        RestClient.Builder restClientBuilder,
        InvitationEmailProperties properties,
        ResendEmailProperties resendProperties) {
      requireConfigured(properties.acceptUrl(), "momens.workspace.invitation.email.accept-url");
      requireConfigured(properties.from(), "momens.workspace.invitation.email.from");
      requireConfigured(properties.replyTo(), "momens.workspace.invitation.email.reply-to");
      RestClient restClient =
          restClientBuilder
              .baseUrl(RESEND_ENDPOINT)
              .defaultHeader("Authorization", "Bearer " + resendProperties.apiKey())
              .requestFactory(requestFactory())
              .build();
      return new ResendEmailClient(restClient, properties.from(), properties.replyTo());
    }

    private static SimpleClientHttpRequestFactory requestFactory() {
      SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
      requestFactory.setConnectTimeout(CONNECT_TIMEOUT);
      requestFactory.setReadTimeout(READ_TIMEOUT);
      return requestFactory;
    }

    private static void requireConfigured(String value, String property) {
      if (value == null || value.isBlank()) {
        throw new IllegalStateException(
            "%s는 %s=resend일 때 반드시 설정해야 합니다.".formatted(property, PROVIDER_PROPERTY));
      }
    }
  }
}
