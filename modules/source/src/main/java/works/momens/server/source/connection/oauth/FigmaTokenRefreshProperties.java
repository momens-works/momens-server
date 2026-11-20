package works.momens.server.source.connection.oauth;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 만료가 {@code window} 안으로 들어온 토큰만 갱신합니다. {@code interval}은 {@link FigmaTokenRefresher}의
 * {@code @Scheduled} placeholder로만 읽지만, 기본값과 검증을 함께 두려고 여기에 선언합니다.
 *
 * <p>{@code window}는 갱신이 연속으로 실패해도 실제 만료 전에 회복할 여유입니다. 이미 만료된 토큰은 대상이 아니므로, 이 기간이 지나도록 갱신하지 못한 연결은
 * 재연결이 필요합니다.
 */
@ConfigurationProperties("momens.source.figma.token-refresh")
public record FigmaTokenRefreshProperties(Duration interval, Duration window) {

  public FigmaTokenRefreshProperties {
    requirePositive(interval, "interval");
    requirePositive(window, "window");
  }

  private static void requirePositive(Duration value, String name) {
    if (value == null || value.isNegative() || value.isZero()) {
      throw new IllegalArgumentException(
          "momens.source.figma.token-refresh." + name + "은 0보다 커야 합니다");
    }
  }
}
