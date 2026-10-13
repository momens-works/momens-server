/** Signal action 기록과 멱등성을 소유합니다. action·task·outbox와 선택적인 Minsu 원장 적재는 같은 트랜잭션으로 처리합니다. */
@ApplicationModule
package works.momens.server.signal.action;

import org.springframework.modulith.ApplicationModule;
