package works.momens.server.mobile.pushdevice.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import works.momens.server.notification.PushInstallationPlatform;

/** 설치 등록 또는 token 갱신 요청(docs/design/signal-push-demo-design.md 8.2절). */
@Schema(description = "push 설치 등록 또는 FCM token 갱신 요청")
public record RegisterPushDeviceRequest(
    @Schema(description = "현재 FCM registration token") @NotBlank String fcmRegistrationToken,
    @Schema(description = "push 알림을 받을 앱이 설치된 기기의 platform입니다.", example = "android") @NotNull
        PushInstallationPlatform platform) {}
