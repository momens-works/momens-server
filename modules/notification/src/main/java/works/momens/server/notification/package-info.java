/**
 * Signal push 소비·발송과 설치 lifecycle을 소유합니다. HTTP 표면은 mobile에 둡니다.
 *
 * <p>내부 의존 방향은 consume → dispatch, consume·dispatch → device, dispatch → fcm입니다. 다른 모듈에는 설치 등록·해제
 * 계약만 공개합니다.
 */
package works.momens.server.notification;
