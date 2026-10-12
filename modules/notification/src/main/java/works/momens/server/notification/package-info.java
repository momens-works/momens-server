/**
 * Notification 모듈 공개 API.
 *
 * <p>모듈 root package는 Spring Modulith 공개 표면이다(docs/rules/architecture.md). Signal 발생 push
 * notification의 소비·발송과 push 설치(FID/FCM token) lifecycle을 이 모듈이 소유한다 (ADR-0009).
 *
 * <ul>
 *   <li>설치 등록·해제 API — {@link works.momens.server.notification.PushDeviceRegistrar}. HTTP 표면({@code
 *       /api/me/push-devices/*})은 mobile 모듈이 소유하고 이 API에 위임한다.
 * </ul>
 *
 * <p>내부는 변경 이유가 다른 네 하위 도메인을 Spring Modulith nested 모듈로 분리한다. {@code device}(설치 원장, aggregate
 * {@code PushInstallation}), {@code consume}(outbox 소비 진행 위치, aggregate {@code
 * NotificationConsumerOffset}), {@code dispatch}(기기별 발송 상태와 배달 실행, aggregate {@code PushDelivery}),
 * {@code fcm}(외부 Firebase adapter). nested 간 협력은 {@code consume → dispatch}({@code
 * PushDispatcher}), {@code consume·dispatch → device}({@code PushInstallationDirectory}), {@code
 * dispatch → fcm}({@code FcmClient}) 단방향 계약으로만 한다. consumer와 발송기는 다른 모듈에 공개할 계약이 없고, outbox
 * 조회·Signal·Project hydrate·수신자 결정은 각 모듈의 public API를 사용한다.
 */
package works.momens.server.notification;
