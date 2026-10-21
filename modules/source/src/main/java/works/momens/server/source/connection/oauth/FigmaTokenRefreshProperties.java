package works.momens.server.source.connection.oauth;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 만료가 {@code window} 안으로 들어온 토큰만 갱신합니다. 같은 prefix의 {@code interval}은 {@link FigmaTokenRefresher}의
 * 주기로 직접 읽습니다.
 *
 * <p>{@code window}는 갱신이 연속으로 실패해도 실제 만료 전에 회복할 여유입니다. 이미 만료된 토큰은 대상이 아니므로, 이 기간이 지나도록 갱신하지 못한 연결은
 * 재연결이 필요합니다.
 */
@Validated
@ConfigurationProperties("momens.source.figma.token-refresh")
public record FigmaTokenRefreshProperties(@NotNull Duration window) {}
