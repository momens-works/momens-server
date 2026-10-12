/**
 * push 설치 등록·해제 위임 하위 도메인.
 *
 * <p>mobile 모듈 안에서 {@code /api/me/push-devices/*}가 담당하는 경계를 Spring Modulith nested 모듈로 명시합니다. 설치
 * lifecycle 정책·영속성은 notification 모듈이 소유하므로, 이 패키지는 notification의 public API({@link
 * works.momens.server.notification.PushDeviceRegistrar})에 위임만 하는 얇은 Controller와 요청 DTO만 둡니다.
 */
@ApplicationModule
package works.momens.server.mobile.pushdevice;

import org.springframework.modulith.ApplicationModule;
