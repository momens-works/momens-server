package works.momens.server.notification.device;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * 소비·발송에 필요한 설치 조회와 무효 token 비활성화 계약입니다. 등록·소유권 이전·해제 정책은 {@link
 * works.momens.server.notification.PushDeviceRegistrar}가 소유합니다.
 */
public interface PushInstallationDirectory {

  List<InstallationSnapshot> findActiveAndroid(Collection<UUID> userIds);

  /** 설치 id로 현재 상태를 조회한다. 없는 id는 결과에서 빠진다. */
  List<InstallationSnapshot> findByIds(Collection<UUID> installationIds);

  /**
   * 클레임한 token이 아직 현재 token일 때만 무효·만료 설치를 발송 대상에서 제외한다. token refresh가 먼저 반영됐거나 이미 비활성이면 아무것도 바꾸지
   * 않는다.
   */
  void deactivateIfTokenMatches(UUID installationId, String claimedToken);

  record InstallationSnapshot(UUID id, UUID userId, String fcmRegistrationToken, boolean active) {}
}
