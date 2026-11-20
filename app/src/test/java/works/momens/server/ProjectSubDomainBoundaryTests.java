package works.momens.server;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * :project 하위 도메인 경계 검증(MOM-0887).
 *
 * <p>{@link ModularityTests}가 검증하지 못하는 범위를 메웁니다. core·task·taskupdate·milestone·blocker·decision은
 * Spring Modulith named interface라서 application module은 여전히 {@code project} 하나입니다. 그래서 Modulith는 하위
 * 도메인 사이의 의존 방향과 순환을 보지 못합니다. 하위 도메인을 nested application module로 선언하면 방향은 검증되지만 다른 상위 모듈이 참조할 수 없게
 * 되므로 (docs/rules/architecture.md) 그 선택지는 쓰지 않습니다.
 *
 * <p>{@code *.internal} 타입은 모두 package-private이라 하위 도메인끼리 서로의 구현 타입을 참조하는 것은 자바 컴파일러가 이미 막습니다. 여기서는
 * 공개 계약을 통한 잘못된 방향만 고정합니다. 새 하위 도메인을 추가하면 이 테스트의 목록도 함께 갱신합니다.
 */
class ProjectSubDomainBoundaryTests {

  private static final String BASE = "works.momens.server.project";

  private final JavaClasses projectClasses = new ClassFileImporter().importPackages(BASE);

  @Test
  void subDomainsAreFreeOfCycles() {
    SlicesRuleDefinition.slices()
        .matching(BASE + ".(*)..")
        .should()
        .beFreeOfCycles()
        .check(projectClasses);
  }

  /**
   * 허용한 의존 방향은 milestone -> core, task -> core, task -> milestone, taskupdate -> task, decision ->
   * core입니다.
   *
   * <p>core는 어떤 하위 도메인도 참조하지 않습니다. 진행률처럼 task를 읽어야 하는 계산은 task가 소유합니다. blocker는 호출자가 확인한 workspace와
   * 대상 식별자를 사용하므로 다른 하위 도메인에 의존하지 않습니다.
   */
  @Test
  void subDomainsDependOnlyInTheAllowedDirection() {
    denyDependency("decision", List.of("task", "taskupdate", "milestone", "blocker"));
    denyDependency("core", List.of("task", "taskupdate", "milestone", "blocker", "decision"));
    denyDependency("blocker", List.of("core", "task", "taskupdate", "milestone", "decision"));
    denyDependency("milestone", List.of("task", "taskupdate", "blocker", "decision"));
    denyDependency("task", List.of("taskupdate", "blocker", "decision"));
    denyDependency("taskupdate", List.of("core", "milestone", "blocker", "decision"));
  }

  private void denyDependency(String subDomain, List<String> forbidden) {
    noClasses()
        .that()
        .resideInAPackage(BASE + "." + subDomain + "..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            forbidden.stream().map(name -> BASE + "." + name + "..").toArray(String[]::new))
        .check(projectClasses);
  }
}
