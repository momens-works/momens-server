package works.momens.server.mobile.pushdevice;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.security.Principal;
import works.momens.server.common.api.ApiException;
import works.momens.server.common.api.CommonErrorCode;
import works.momens.server.mobile.pushdevice.dto.request.RegisterPushDeviceRequest;

@Tag(name = "PushDevice", description = "push 설치(FID/FCM token) 등록·해제 API")
interface PushDeviceControllerDocs {

  @Operation(
      operationId = "registerPushDevice",
      summary = "push 설치 등록 또는 token 갱신",
      description =
          "Firebase Installation ID(FID) 단위로 설치를 등록하거나 FCM token을 갱신합니다. 같은 FID의 재요청은 token을 갱신하고"
              + " 활성화하며, 다른 사용자에게 귀속된 FID면 현재 인증 사용자에게 소유권을 이전합니다. 같은 활성 token이 다른 FID에 연결돼 있으면"
              + " 이전 연결을 비활성화합니다.")
  @ApiResponse(responseCode = "204", description = "생성·갱신 모두 성공.")
  @ApiException(CommonErrorCode.class)
  void register(
      @Parameter(description = "Firebase Installation ID") String firebaseInstallationId,
      RegisterPushDeviceRequest request,
      Principal principal);

  @Operation(
      operationId = "unregisterPushDevice",
      summary = "push 설치 해제",
      description =
          "현재 인증 사용자가 소유한 설치를 비활성화합니다. Android 앱은 로그아웃 직전에 호출합니다. 이미 비활성화됐거나 없는 설치도 204로 멱등"
              + " 처리하며, 다른 사용자가 소유한 활성 설치는 해제하지 않습니다.")
  @ApiResponse(responseCode = "204", description = "해제 성공(신규 또는 멱등 replay).")
  @ApiException(CommonErrorCode.class)
  void unregister(
      @Parameter(description = "Firebase Installation ID") String firebaseInstallationId,
      Principal principal);
}
