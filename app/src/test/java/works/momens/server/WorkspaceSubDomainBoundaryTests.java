package works.momens.server;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@code :workspace} 모듈의 하위 도메인 경계를 검증합니다(MOM-0894).
 *
 * <p>{@code ModularityTests}에서 검증하지 못하는 범위를 보완합니다. {@code core}, {@code membership}, {@code
 * invitation}, {@code label}은 Spring Modulith의 named interface이므로 애플리케이션 모듈은 {@code workspace}로
 * 구성됩니다. 따라서 Modulith는 하위 도메인 간 의존 방향과 순환 참조를 검증하지 않습니다. 하위 도메인을 nested application module로 선언하면 의존
 * 방향을 검증할 수 있지만, 다른 상위 모듈에서 참조할 수 없게 됩니다({@code docs/rules/architecture.md} 참고). 따라서 이 방식은 사용하지
 * 않습니다.
 *
 * <p>{@code email}은 nested application module이지만 외부에 공개하는 계약이 없습니다. 따라서 다른 하위 도메인과 함께 검사 대상에 포함하되,
 * 의존 방향만 고정합니다. {@code internal} 패키지의 타입은 대부분 package-private이므로 하위 도메인이 서로의 구현을 참조하는 것은 자바 컴파일러가
 * 이미 제한합니다. 이 테스트에서는 공개 계약을 통한 잘못된 의존 방향을 검증합니다.
 *
 * <p>새 하위 도메인을 추가할 때는 이 테스트의 검사 대상도 함께 갱신해야 합니다.
 */
class WorkspaceSubDomainBoundaryTests {

  private static final String BASE = "works.momens.server.workspace";

  private final JavaClasses workspaceClasses = new ClassFileImporter().importPackages(BASE);

  @Test
  void subDomainsAreFreeOfCycles() {
    SlicesRuleDefinition.slices()
        .matching(BASE + ".(*)..")
        .should()
        .beFreeOfCycles()
        .check(workspaceClasses);
  }

  /**
   * 하위 도메인 간 의존성이 허용된 방향으로만 형성되어 있는지 검증합니다.
   *
   * <p>허용되는 의존 방향은 {@code core}에서 {@code membership}으로 향하는 경우와 {@code invitation}에서 {@code core},
   * {@code membership}, {@code email}로 향하는 경우뿐입니다.
   *
   * <p>{@code core}가 {@code membership}을 참조하는 이유는 두 가지입니다. 워크스페이스를 생성할 때 요청자를 owner 멤버로 등록하고, 사용자가
   * 속한 워크스페이스를 조회할 때 먼저 멤버십을 확인하기 때문입니다. {@code membership}과 {@code label}은 다른 하위 도메인을 참조하지 않습니다.
   */
  @Test
  void subDomainsDependOnlyInTheAllowedDirection() {
    denyDependency("core", List.of("invitation", "label", "email"));
    denyDependency("membership", List.of("core", "invitation", "label", "email"));
    denyDependency("invitation", List.of("label"));
    denyDependency("label", List.of("core", "membership", "invitation", "email"));
    denyDependency("email", List.of("core", "membership", "invitation", "label"));
  }

  private void denyDependency(String subDomain, List<String> forbidden) {
    noClasses()
        .that()
        .resideInAPackage(BASE + "." + subDomain + "..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            forbidden.stream().map(name -> BASE + "." + name + "..").toArray(String[]::new))
        .check(workspaceClasses);
  }
}
