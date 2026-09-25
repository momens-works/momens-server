package works.momens.server.notification;

import java.util.UUID;

/**
 * push 설치(FID/FCM token) 등록·해제 public API(docs/design/signal-push-demo-design.md 8절).
 *
 * <p>Firebase Installation ID(FID)가 앱 설치본의 canonical identity이고, FCM registration token은 FID의 현재 전송
 * 주소다. 한 FID는 한 시점에 한 활성 사용자에게만 귀속된다.
 */
public interface PushDeviceRegistrar {

  /**
   * 설치를 등록하거나 token을 갱신한다. 같은 FID의 재요청은 token을 갱신하고 활성화하며, 다른 사용자에게 귀속된 FID면 현재 사용자에게 원자적으로 소유권을
   * 이전한다. 동일한 활성 FCM token이 다른 FID에 연결돼 있으면 이전 연결을 비활성화한다.
   *
   * @param platform 등록할 기기의 platform입니다. 허용하는 값은 `PushInstallationPlatform`이 정의합니다.
   * @throws works.momens.server.common.api.BusinessException FID나 token이 공백이면 {@code
   *     COMMON_VALIDATION_FAILED}(400)를 던집니다.
   */
  void register(
      UUID userId,
      String firebaseInstallationId,
      String fcmRegistrationToken,
      PushInstallationPlatform platform);

  /**
   * 현재 사용자가 소유한 설치를 비활성화한다. 이미 비활성화됐거나 없는 설치, 다른 사용자가 소유한 설치는 아무것도 바꾸지 않는다(멱등). 물리 삭제하지 않아 같은 사용자의
   * 재등록과 token lifecycle을 안전하게 처리한다.
   */
  void unregister(UUID userId, String firebaseInstallationId);
}
