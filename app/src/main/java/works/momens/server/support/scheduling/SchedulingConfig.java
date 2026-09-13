package works.momens.server.support.scheduling;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 스케줄링 인프라 활성화(MOM-0816).
 *
 * <p><b>{@code @Scheduled} 빈을 추가하면 {@code spring.task.scheduling.pool.size}를 함께 본다.</b> 풀이 모자라면 오래
 * 블로킹하는 주기가 다른 주기를 굶기는데, 그 실패가 조용하다. 특히 {@code minsu}의 지표 스냅샷이 밀리면 원장 상태 gauge가 낡은 값으로 굳어, 하필 관측이
 * 필요한 순간에 관측이 멈춘다.
 *
 * <p>인프라 활성화는 조립 모듈인 {@code app}이 소유하고 항상 켠다. {@code @Scheduled} 빈의 실행 여부는 각 모듈이 자신의 설정으로 판정한다(예:
 * notification의 폴링 스케줄러는 {@code momens.notification.push.enabled}로 등록 자체가 갈린다). 게이트를 특정 모듈이 소유하면 다른
 * 모듈의 스케줄러가 그 모듈의 설정에 우연히 종속되므로 여기로 올렸다.
 *
 * <p>MOM-0816 당시에는 이 클래스가 없어도 스케줄링이 활성화되었습니다. 런타임 클래스패스에 있던 spring-modulith-moments의 {@code
 * MomentsAutoConfiguration}에 클래스 레벨 {@code @EnableScheduling}이 선언되어 있었기 때문입니다. 당시에는 {@code
 * spring.modulith.moments.enabled=false}로 해당 자동 구성을 비활성화하고, 스케줄링을 활성화하는 주체를 이 클래스로 한정했습니다.
 *
 * <p>MOM-0921부터는 Spring Modulith가 런타임 클래스패스에 없으므로 해당 자동 구성 자체가 로드되지 않습니다. 이에 따라 프로퍼티로 자동 구성을 비활성화하는
 * 설정도 제거했으며, 이 클래스가 스케줄링을 활성화하는 유일한 지점입니다. 이 클래스가 제거되면 스케줄러가 별도 오류 없이 중단되므로, 이 계약은 {@code
 * SchedulingConfigIntegrationTest}에서 검증합니다.
 *
 * <p>기본 {@code TaskScheduler}는 풀 크기가 1이라 스케줄러들이 한 스레드에서 직렬 실행된다. 현재는 1초 주기 push 폴링 하나뿐이라 문제가 없지만,
 * 오래 걸리는 스케줄러가 추가되면 {@code spring.task.scheduling.pool.size}를 함께 재검토한다.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {}
