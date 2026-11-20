/**
 * Minsu의 공유 LLM 경계.
 *
 * <p>provider 중립 호출 계약, 배포 설정 검증과 model 선택을 Spring Modulith nested module로 명시합니다. Google Gen AI SDK
 * 구현은 {@code google} 내부 패키지에 격리하며, task draft와 향후 query는 이 패키지의 계약만 사용합니다.
 */
@ApplicationModule
package works.momens.server.minsu.llm;

import org.springframework.modulith.ApplicationModule;
