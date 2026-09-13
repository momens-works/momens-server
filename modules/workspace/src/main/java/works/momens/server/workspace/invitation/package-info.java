/**
 * 워크스페이스 초대 하위 도메인입니다.
 *
 * <p>{@code WorkspaceInvitation} aggregate와 초대 상태 전이 규칙을 관리하며, 외부에는 이 패키지의 공개 계약만 노출합니다. 구현은 {@code
 * internal} 패키지에 둡니다. 초대를 수락할 때는 {@code core}와 {@code membership}의 공개 계약으로 워크스페이스와 멤버십을 확인하고, 이메일
 * 발송은 {@code email} 하위 모듈에 위임합니다.
 */
@org.springframework.modulith.NamedInterface
package works.momens.server.workspace.invitation;
