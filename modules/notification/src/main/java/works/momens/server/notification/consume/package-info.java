/** outbox 소비 진행 위치와 수신자 결정을 소유합니다. 기기별 재시도·FCM 발송은 dispatch에 위임합니다. */
@ApplicationModule
package works.momens.server.notification.consume;

import org.springframework.modulith.ApplicationModule;
