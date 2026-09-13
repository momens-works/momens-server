/**
 * 워크스페이스 멤버십과 권한을 관리하는 하위 도메인입니다.
 *
 * <p>{@code WorkspaceMember} aggregate와 역할 판정 규칙을 관리하며, 외부에는 이 패키지의 공개 계약만 노출합니다. 구현은 {@code
 * internal} 패키지에 둡니다. MOM-70에서는 {@code access}라는 이름으로 만들었으며, MOM-0894에서 공개 계약의 용어에 맞춰 {@code
 * membership}으로 변경했습니다.
 */
@org.springframework.modulith.NamedInterface
package works.momens.server.workspace.membership;
