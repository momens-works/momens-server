package works.momens.server.support.scheduling;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 스케줄링 활성화는 app이 소유하며 각 모듈은 자기 스케줄러의 등록 여부만 결정합니다.
 *
 * <p>이 설정을 제거하면 스케줄러가 조용히 중단되므로 {@code SchedulingConfigIntegrationTest}로 검증합니다. 스케줄러 추가 시 {@code
 * spring.task.scheduling.pool.size}를 함께 확인해 다른 주기와 지표 갱신이 밀리지 않게 합니다.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {}
