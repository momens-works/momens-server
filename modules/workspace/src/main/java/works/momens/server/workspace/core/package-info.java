/**
 * 워크스페이스 하위 도메인입니다.
 *
 * <p>{@code Workspace} aggregate와 slug 규칙을 관리하며, 외부에는 이 패키지의 공개 계약만 노출합니다. 구현은 {@code internal}
 * 패키지에 둡니다. slug는 {@code Workspace}의 속성이므로 별도 하위 도메인으로 분리하지 않습니다.
 *
 * <p>사용자가 속한 워크스페이스를 조회할 때는 {@code membership}의 공개 계약으로 멤버십을 확인한 뒤, 확정된 워크스페이스 ID를 기준으로 이 하위 도메인의
 * 테이블을 조회합니다.
 */
@org.springframework.modulith.NamedInterface
package works.momens.server.workspace.core;
