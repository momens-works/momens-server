/**
 * Mobile 모듈 공개 API.
 *
 * <p>모듈 root package는 Spring Modulith 공개 표면이다(docs/rules/architecture.md). 화면별 조합 서비스·DTO·컨트롤러는
 * {@code bootstrap}·{@code roster}·{@code board}·{@code brief}·{@code signal} nested 모듈에
 * 두고(MOM-0799), 이 패키지는 두 개 이상의 nested 모듈이 공유하거나 모듈 밖(테스트 포함)에서 참조해도 되는 계약만 둔다.
 *
 * <ul>
 *   <li>시간 seam — {@link works.momens.server.mobile.MobileClock}. 브리프의 "오늘"이 현재 시각에 의존하므로 통합 테스트가
 *       내부를 넘겨보지 않고 고정 Clock으로 덮어쓸 수 있게 공개한다. 배선은 {@code brief.MobileTimeConfig}가 소유한다.
 *   <li>priority 저장값 해석 — {@link works.momens.server.mobile.MobileTaskPriority}. {@code board}(태스크
 *       상세·보드)와 {@code brief}(현재 우선순위 구성) 양쪽이 같은 해석 규칙을 참조해야 해서 어느 한쪽 nested 모듈이 아니라 모듈 root에 둔다.
 * </ul>
 */
package works.momens.server.mobile;
