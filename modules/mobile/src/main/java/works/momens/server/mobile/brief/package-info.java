/**
 * 브리프 화면 하위 도메인.
 *
 * <p>mobile 모듈 안에서 {@code GET /api/mobile/projects/{projectId}/brief}와 {@code
 * .../brief/signal-summary}가 담당하는 경계를 Spring Modulith nested 모듈로 명시합니다(MOM-0799). project(스냅샷,
 * 태스크)·signal(당일 시그널 요약)· workspace(멤버십)의 public API만 조합하고 도메인 정책을 소유하지 않습니다. 현재 우선순위 구성에 쓰는
 * priority 저장값 해석은 {@code board}와 공유하는 규칙이라 모듈 root의 {@link
 * works.momens.server.mobile.MobileTaskPriority}를 그대로 참조합니다.
 */
@org.springframework.modulith.ApplicationModule
package works.momens.server.mobile.brief;
