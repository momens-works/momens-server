/**
 * 워크스페이스 기능의 Spring Modulith 경계를 정의합니다.
 *
 * <p>공개 계약은 하위 도메인별 named interface인 {@code core}, {@code membership}, {@code invitation}, {@code
 * label}에 두고, 이 패키지는 네임스페이스와 모듈 선언을 관리합니다. {@code WorkspaceErrorCode}만 예외적으로 이 패키지에 둡니다. 세 하위 도메인이
 * 이 enum의 상수를 함께 사용하므로 특정 하위 도메인에 배치하면 하위 도메인 간 참조가 순환하기 때문입니다.
 */
@org.springframework.modulith.ApplicationModule
package works.momens.server.workspace;
