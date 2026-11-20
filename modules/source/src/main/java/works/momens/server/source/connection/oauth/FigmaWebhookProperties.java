package works.momens.server.source.connection.oauth;

import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** 실제 수신 주소와 worker가 검증하는 passcode는 같은 운영 배선에서 주입합니다. */
@Validated
@ConfigurationProperties("momens.source.figma.webhook")
public record FigmaWebhookProperties(
    @Size(max = 2048) String endpoint, @Size(max = 100) String passcode) {
  boolean isConfigured() {
    return endpoint != null && !endpoint.isBlank() && passcode != null && !passcode.isBlank();
  }
}
