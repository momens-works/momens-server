package works.momens.server.source.connection.oauth;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;
import org.springframework.web.client.RestClientException;
import works.momens.server.source.connection.SourceCredentialRepository;

/** 비활성화·재설정·실패 보상에서 지정한 webhook만 best effort로 정리합니다. */
@Component
@RequiredArgsConstructor
@Slf4j
public class FigmaWebhookCleaner {

  private final SourceCredentialRepository credentialRepository;
  private final TokenEncryptor tokenEncryptor;
  private final FigmaWebhookClient client;

  public void delete(UUID connectionId, String webhookId) {
    try {
      var credential = credentialRepository.findById(connectionId);
      String token =
          credential.isPresent()
              ? tokenEncryptor.decrypt(credential.get().getAccessTokenEnc())
              : "";
      if (token.isBlank()) {
        log.warn(
            "Figma webhook cleanup skipped connectionId={} webhookId={} reason=missing_token",
            connectionId,
            webhookId);
        return;
      }
      deleteWithToken(connectionId, webhookId, token);
    } catch (DataAccessException
        | TransactionException
        | IllegalArgumentException
        | IllegalStateException e) {
      log.warn(
          "Figma webhook cleanup failed connectionId={} webhookId={} failureType={}",
          connectionId,
          webhookId,
          e.getClass().getSimpleName());
    }
  }

  void deleteWithToken(UUID connectionId, String webhookId, String token) {
    try {
      int status = client.delete(token, webhookId);
      if (status < 200 || status >= 300) {
        log.warn(
            "Figma webhook cleanup failed connectionId={} webhookId={} status={}",
            connectionId,
            webhookId,
            status);
      }
    } catch (IllegalArgumentException | IllegalStateException | RestClientException e) {
      log.warn(
          "Figma webhook cleanup failed connectionId={} webhookId={} failureType={}",
          connectionId,
          webhookId,
          e.getClass().getSimpleName());
    }
  }
}
