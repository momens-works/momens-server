/**
 * Signal task draft 생성 하위 도메인.
 *
 * <p>동기 draft 준비와 비동기 생성 원장 lifecycle — 적재, claim, 실행, 재시도, 결과 반영, 상태 조회 — 을 Spring Modulith nested
 * module로 명시합니다. 다른 Gradle 모듈에는 {@link works.momens.server.minsu.SignalTaskDraftGenerator}와 {@link
 * works.momens.server.minsu.TaskDraftStatusReader} 등 Minsu root의 공개 계약만 노출합니다. LLM 호출은 {@code llm}
 * nested module의 provider 중립 계약을 사용합니다.
 */
@ApplicationModule
package works.momens.server.minsu.draft;

import org.springframework.modulith.ApplicationModule;
