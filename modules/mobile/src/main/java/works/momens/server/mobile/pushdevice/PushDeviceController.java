package works.momens.server.mobile.pushdevice;

import jakarta.validation.Valid;
import java.security.Principal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import works.momens.server.common.api.CurrentUser;
import works.momens.server.mobile.pushdevice.dto.request.RegisterPushDeviceRequest;
import works.momens.server.notification.PushDeviceRegistrar;

/**
 * push 설치 등록·해제 엔드포인트.
 *
 * <p>설치 lifecycle 정책·영속성은 notification 모듈이 소유하고 이 컨트롤러는 위임만 합니다. dev 도구가 아니라 worker 전환 이후에도 필요한 인증
 * 사용자 API라 {@code @DevOnly} 게이트가 없습니다. 보호 체인의 기본 인증 대상이라 별도 보안 설정이 없습니다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/me/push-devices")
class PushDeviceController implements PushDeviceControllerDocs {

  private final PushDeviceRegistrar pushDeviceRegistrar;

  @Override
  @PutMapping(path = "/{firebaseInstallationId}", version = "1")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void register(
      @PathVariable String firebaseInstallationId,
      @Valid @RequestBody RegisterPushDeviceRequest request,
      Principal principal) {
    pushDeviceRegistrar.register(
        CurrentUser.id(principal),
        firebaseInstallationId,
        request.fcmRegistrationToken(),
        request.platform());
  }

  @Override
  @DeleteMapping(path = "/{firebaseInstallationId}", version = "1")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void unregister(@PathVariable String firebaseInstallationId, Principal principal) {
    pushDeviceRegistrar.unregister(CurrentUser.id(principal), firebaseInstallationId);
  }
}
