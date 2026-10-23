package works.momens.server.source.connection.oauth;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;
import works.momens.server.source.connection.SourceCredential;
import works.momens.server.source.connection.SourceCredentialRepository;
import works.momens.server.source.connection.oauth.FigmaTokenRefreshClient.RefreshFailedException;
import works.momens.server.source.connection.oauth.FigmaTokenRefreshClient.RefreshedToken;

/**
 * 만료가 다가온 Figma access token을 미리 갱신합니다. worker는 서버를 거치지 않고 DB의 token을 읽으므로 사용 시점이 아니라 만료 전에 갱신해야
 * 합니다.
 *
 * <p>Figma는 앱·사용자당 access token을 하나만 유지하고 새로 발급하면 이전 token을 무효화합니다. 그래서 갱신 단위는 연결이 아니라 Figma 사용자이며,
 * 받은 token을 그 사용자의 모든 credential 행에 함께 씁니다.
 *
 * <p>실패를 따로 복구하지 않습니다. 저장하지 못하면 {@code expires_at}이 그대로라 다음 주기에 다시 선택되고, refresh token은 재사용할 수 있어
 * 다시 갱신하면 회복됩니다.
 */
@Slf4j
@Component
class FigmaTokenRefresher {

  private final OAuthProviderRegistry providerRegistry;
  private final SourceCredentialRepository credentials;
  private final TokenEncryptor encryptor;
  private final FigmaTokenRefreshClient client;
  private final FigmaTokenRefreshProperties properties;
  private final TransactionTemplate saveTransaction;

  FigmaTokenRefresher(
      OAuthProviderRegistry providerRegistry,
      SourceCredentialRepository credentials,
      TokenEncryptor encryptor,
      FigmaTokenRefreshClient client,
      FigmaTokenRefreshProperties properties,
      PlatformTransactionManager transactionManager) {
    this.providerRegistry = providerRegistry;
    this.credentials = credentials;
    this.encryptor = encryptor;
    this.client = client;
    this.properties = properties;
    this.saveTransaction = new TransactionTemplate(transactionManager);
  }

  @Scheduled(
      initialDelayString = "1m",
      fixedDelayString = "${momens.source.figma.token-refresh.interval}")
  void refreshExpiring() {
    try {
      refreshDue();
    } catch (RuntimeException e) {
      log.error("Figma token refresh run failed", e);
    }
  }

  /**
   * Figma 호출 동안 DB transaction과 연결을 잡지 않습니다. 커넥션 풀이 작아 호출 시간만큼 연결을 점유하면 다른 요청이 대기하기 때문입니다. 사용자별 저장만
   * 짧은 transaction으로 commit해 한 사용자의 저장 실패가 다른 사용자의 갱신을 막지 않게 합니다.
   *
   * <p>인스턴스 간 lock은 두지 않습니다. 실행이 겹치는 것은 배포 중 인스턴스가 겹칠 때뿐이므로, 호출 직전에 아직 갱신 대상인지 다시 확인해 다른 인스턴스가 이미
   * 갱신한 사용자를 건너뜁니다. 두 인스턴스가 같은 사용자를 1초 안팎에 함께 갱신하거나 갱신이 OAuth 재연결·configure와 겹치면 Figma에서 나중에 발급된
   * token과 DB에 나중에 저장된 token이 어긋날 수 있으며, 재연결을 다시 하면 회복됩니다.
   */
  void refreshDue() {
    OAuthProvider provider =
        providerRegistry
            .find(OAuthProviderRegistry.FIGMA)
            .filter(OAuthProvider::isConfigured)
            .orElse(null);
    if (provider == null) {
      return;
    }
    Instant now = Instant.now();
    for (String figmaUserId :
        credentials.findFigmaUsersExpiringBetween(now, now.plus(properties.window()))) {
      refreshUser(provider, figmaUserId);
    }
  }

  private void refreshUser(OAuthProvider provider, String figmaUserId) {
    List<SourceCredential> rows = credentials.findFigmaByUser(figmaUserId);
    Instant now = Instant.now();
    Instant until = now.plus(properties.window());
    if (rows.stream()
        .map(SourceCredential::getExpiresAt)
        .noneMatch(at -> at != null && at.isAfter(now) && !at.isAfter(until))) {
      return;
    }
    // 재연결 시점에 따라 행마다 refresh token이 다를 수 있으므로 가장 최근에 저장된 값을 씁니다.
    byte[] refreshTokenEnc =
        rows.stream()
            .max(Comparator.comparing(SourceCredential::getUpdatedAt))
            .orElseThrow()
            .getRefreshTokenEnc();
    String refreshToken;
    try {
      refreshToken = encryptor.decrypt(refreshTokenEnc);
    } catch (IllegalArgumentException | IllegalStateException e) {
      log.warn(
          "Figma token refresh skipped figmaUserId={} reason=undecryptable_refresh_token",
          figmaUserId);
      return;
    }
    if (refreshToken.isBlank()) {
      log.warn(
          "Figma token refresh skipped figmaUserId={} reason=missing_refresh_token", figmaUserId);
      return;
    }
    RefreshedToken refreshed;
    try {
      refreshed = client.refresh(provider, refreshToken);
    } catch (RefreshFailedException e) {
      log.warn(
          "Figma token refresh failed figmaUserId={} reauthRequired={} reason={}",
          figmaUserId,
          e.reauthRequired(),
          e.getMessage());
      return;
    }
    try {
      int saved = save(figmaUserId, refreshed, refreshTokenEnc);
      log.info("Figma token refreshed figmaUserId={} credentials={}", figmaUserId, saved);
    } catch (DataAccessException | TransactionException e) {
      log.warn(
          "Figma token refresh save failed figmaUserId={} failureType={}",
          figmaUserId,
          e.getClass().getSimpleName());
    }
  }

  private int save(String figmaUserId, RefreshedToken refreshed, byte[] refreshTokenEnc) {
    byte[] accessTokenEnc = encryptor.encrypt(refreshed.accessToken());
    Integer saved =
        saveTransaction.execute(
            tx -> {
              List<SourceCredential> rows = credentials.findFigmaByUser(figmaUserId);
              rows.forEach(
                  row ->
                      row.replaceTokens(
                          accessTokenEnc,
                          refreshTokenEnc,
                          refreshed.tokenType() == null
                              ? row.getTokenType()
                              : refreshed.tokenType(),
                          row.getScope(),
                          refreshed.expiresAt()));
              credentials.saveAll(rows);
              return rows.size();
            });
    return saved == null ? 0 : saved;
  }
}
