/**
 * 워크스페이스 단위의 라벨 발급 하위 도메인입니다.
 *
 * <p>{@code WorkspaceLabelSequence} aggregate와 발급 카운터를 관리하며, 외부에는 {@code LabelAllocator}만 공개합니다.
 * 구현은 {@code internal} 패키지에 둡니다. 라벨 발급이 호출자의 트랜잭션에 참여하는 이유는 {@code docs/design/module-map.md}의
 * workspace 절에 설명되어 있습니다.
 */
@org.springframework.modulith.NamedInterface
package works.momens.server.workspace.label;
