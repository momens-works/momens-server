package works.momens.server.source.connection.oauth;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClientException;
import works.momens.server.common.api.BusinessException;
import works.momens.server.source.BeginInstallCommand;
import works.momens.server.source.CompleteInstallCommand;
import works.momens.server.source.CompletedInstall;
import works.momens.server.source.SourceConnectionDetail;
import works.momens.server.source.SourceErrorCode;
import works.momens.server.source.SourceInstaller;
import works.momens.server.source.connection.SourceConnection;
import works.momens.server.source.connection.SourceConnectionRepository;
import works.momens.server.source.connection.SourceConnectionStatus;
import works.momens.server.source.connection.SourceCredential;
import works.momens.server.source.connection.SourceCredentialRepository;

/**
 * 외부 source 연결 승인 흐름의 시작과 완료 단계를 구현합니다.
 *
 * <p>실패 지점과 HTTP status는 레거시와 동일합니다. 승인 코드나 state가 없으면 400, state 검증에 실패해도 400, provider와 토큰 또는 사용자
 * 정보를 주고받는 데 실패하면 502로 처리합니다.
 *
 * <p>Figma 연결만 대기 상태로 저장합니다. 수집을 시작하기 전에 별도 설정을 완료해야 하기 때문이며, 레거시와 동일한 동작입니다.
 */
@Component
@RequiredArgsConstructor
class SourceInstallerImpl implements SourceInstaller {

  private final OAuthProviderRegistry providerRegistry;
  private final OAuthStateSigner stateSigner;
  private final ProviderOAuthClient providerOAuthClient;
  private final TokenEncryptor tokenEncryptor;
  private final SourceConnectionRepository sourceConnectionRepository;
  private final SourceCredentialRepository sourceCredentialRepository;
  private final SourceOAuthProperties properties;
  private final TransactionTemplate transactionTemplate;

  @Override
  public String beginInstall(BeginInstallCommand command) {
    OAuthProvider provider = configuredProvider(command.provider());
    String state =
        stateSigner.sign(
            new OAuthState(command.workspaceId(), command.userId(), provider.sourceType()));
    return provider.authorizeUrl(state);
  }

  @Override
  public CompletedInstall completeInstall(CompleteInstallCommand command) {
    if (isBlank(command.code()) || isBlank(command.state())) {
      throw new BusinessException(SourceErrorCode.SOURCE_OAUTH_INVALID_REQUEST, Map.of());
    }
    OAuthState state = stateSigner.verify(command.state());
    if (state == null) {
      throw new BusinessException(SourceErrorCode.SOURCE_OAUTH_INVALID_STATE, Map.of());
    }
    OAuthProvider provider = configuredProvider(state.provider());

    Map<String, Object> tokenResponse = exchange(provider, command.code());
    String accessToken = String.valueOf(tokenResponse.getOrDefault("access_token", ""));
    if (accessToken.isBlank() || "null".equals(accessToken)) {
      throw exchangeFailed(provider, "empty access token");
    }
    // 만료 시각이 없으면 Figma token 갱신 대상에서 영구히 빠지므로 저장하지 않습니다.
    if (OAuthProviderRegistry.FIGMA.equals(provider.sourceType())
        && ProviderOAuthClient.expiresAt(tokenResponse) == null) {
      throw exchangeFailed(provider, "invalid expires_in");
    }
    ProviderIdentity identity = readIdentity(provider, tokenResponse);

    SourceConnectionDetail saved =
        transactionTemplate.execute(
            status -> {
              SourceConnection connection = upsertConnection(state, provider, identity);
              credentialTargets(provider, identity, connection)
                  .forEach(id -> upsertCredential(id, tokenResponse, accessToken));
              return toDetail(connection);
            });
    return new CompletedInstall(saved, properties.successRedirectUri());
  }

  private OAuthProvider configuredProvider(String sourceType) {
    OAuthProvider provider =
        providerRegistry
            .find(sourceType)
            .orElseThrow(
                () ->
                    new BusinessException(
                        SourceErrorCode.SOURCE_UNSUPPORTED_PROVIDER,
                        Map.of("provider", String.valueOf(sourceType))));
    if (!provider.isConfigured()) {
      throw new BusinessException(
          SourceErrorCode.SOURCE_PROVIDER_UNCONFIGURED, Map.of("provider", provider.sourceType()));
    }
    return provider;
  }

  private Map<String, Object> exchange(OAuthProvider provider, String code) {
    try {
      return providerOAuthClient.exchange(provider, code);
    } catch (RestClientException e) {
      throw exchangeFailed(provider, "token exchange");
    }
  }

  private ProviderIdentity readIdentity(OAuthProvider provider, Map<String, Object> tokenResponse) {
    ProviderIdentity identity;
    try {
      identity =
          provider
              .definition()
              .identityMapper()
              .map(tokenResponse, providerOAuthClient.fetchIdentity(provider, tokenResponse));
    } catch (RestClientException | IllegalStateException e) {
      throw exchangeFailed(provider, "identity fetch");
    }
    if (identity == null || isBlank(identity.externalId())) {
      throw exchangeFailed(provider, "empty provider identity");
    }
    return identity;
  }

  private SourceConnection upsertConnection(
      OAuthState state, OAuthProvider provider, ProviderIdentity identity) {
    SourceConnectionStatus status =
        OAuthProviderRegistry.FIGMA.equals(provider.sourceType())
            ? SourceConnectionStatus.PENDING
            : SourceConnectionStatus.ACTIVE;
    Instant now = Instant.now();
    List<SourceConnection> existing =
        OAuthProviderRegistry.FIGMA.equals(provider.sourceType())
            ? sourceConnectionRepository.findFigmaForReconnect(
                state.workspaceId(), identity.externalId())
            : sourceConnectionRepository
                .findByWorkspaceIdAndSourceTypeAndExternalWorkspaceIdOrderByCreatedAtAsc(
                    state.workspaceId(), provider.sourceType(), identity.externalId());
    if (!existing.isEmpty()) {
      existing.forEach(
          connection -> {
            Map<String, Object> metadata = identity.metadata();
            if (OAuthProviderRegistry.FIGMA.equals(provider.sourceType())) {
              // configure의 마지막 검증과 같은 행 잠금을 사용합니다. 재승인 후에도 기존 webhook 정리가 가능해야 합니다.
              metadata = new LinkedHashMap<>();
              if (connection.getMetadata() != null) {
                metadata.putAll(connection.getMetadata());
              }
              metadata.putAll(identity.metadata());
            }
            sourceConnectionRepository.reconnect(
                connection.getId(), status, identity.externalName(), state.userId(), now, metadata);
          });
      return sourceConnectionRepository.findById(existing.getFirst().getId()).orElseThrow();
    }
    SourceConnection connection =
        SourceConnection.builder()
            .workspaceId(state.workspaceId())
            .sourceType(provider.sourceType())
            .status(status)
            .externalWorkspaceId(identity.externalId())
            .externalWorkspaceName(identity.externalName())
            .connectedByUserId(state.userId())
            .connectedAt(now)
            .metadata(identity.metadata())
            .build();
    return sourceConnectionRepository.saveAndFlush(connection);
  }

  /**
   * Figma는 앱·사용자당 access token을 하나만 유지하므로 새 token을 받으면 같은 Figma 사용자의 다른 연결이 가진 token은 무효가 됩니다. 그래서
   * 워크스페이스와 상태를 가리지 않고 그 사용자의 모든 연결에 같은 token을 저장합니다.
   */
  private List<UUID> credentialTargets(
      OAuthProvider provider, ProviderIdentity identity, SourceConnection connection) {
    if (!OAuthProviderRegistry.FIGMA.equals(provider.sourceType())) {
      return List.of(connection.getId());
    }
    return sourceConnectionRepository
        .findBySourceTypeAndExternalWorkspaceIdOrderById(
            OAuthProviderRegistry.FIGMA, identity.externalId())
        .stream()
        .map(SourceConnection::getId)
        .toList();
  }

  private void upsertCredential(
      UUID connectionId, Map<String, Object> tokenResponse, String accessToken) {
    byte[] accessTokenEnc = tokenEncryptor.encrypt(accessToken);
    byte[] refreshTokenEnc = tokenEncryptor.encrypt(stringOrNull(tokenResponse, "refresh_token"));
    String tokenType = stringOrNull(tokenResponse, "token_type");
    String scope = stringOrNull(tokenResponse, "scope");
    Instant expiresAt = ProviderOAuthClient.expiresAt(tokenResponse);
    SourceCredential credential =
        sourceCredentialRepository
            .findById(connectionId)
            .map(
                existing -> {
                  existing.replaceTokens(
                      accessTokenEnc, refreshTokenEnc, tokenType, scope, expiresAt);
                  return existing;
                })
            .orElseGet(
                () ->
                    SourceCredential.builder()
                        .connectionId(connectionId)
                        .accessTokenEnc(accessTokenEnc)
                        .refreshTokenEnc(refreshTokenEnc)
                        .tokenType(tokenType)
                        .scope(scope)
                        .expiresAt(expiresAt)
                        .build());
    sourceCredentialRepository.save(credential);
  }

  private static SourceConnectionDetail toDetail(SourceConnection connection) {
    return new SourceConnectionDetail(
        connection.getId(),
        connection.getWorkspaceId(),
        connection.getSourceType(),
        connection.getStatus().name(),
        connection.getExternalWorkspaceId(),
        connection.getExternalWorkspaceName(),
        connection.getConnectedByUserId(),
        connection.getConnectedAt(),
        connection.getLastSyncedAt(),
        connection.getDisabledAt(),
        connection.getResyncRequestedAt(),
        connection.getCapturesReadCount(),
        connection.getCandidatesExtractedCount(),
        connection.getMetadata(),
        connection.getCreatedAt(),
        connection.getUpdatedAt());
  }

  private BusinessException exchangeFailed(OAuthProvider provider, String stage) {
    return new BusinessException(
        SourceErrorCode.SOURCE_OAUTH_EXCHANGE_FAILED,
        Map.of("provider", provider.sourceType(), "stage", stage));
  }

  private static String stringOrNull(Map<String, Object> body, String key) {
    Object value = body.get(key);
    if (value == null) {
      return null;
    }
    String text = String.valueOf(value);
    return text.isBlank() ? null : text;
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
