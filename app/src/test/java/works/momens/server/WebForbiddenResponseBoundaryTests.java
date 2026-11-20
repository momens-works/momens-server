package works.momens.server;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;
import works.momens.server.common.api.CommonErrorCode;

/**
 * web 패키지의 {@code AUTH_FORBIDDEN} 상수 접근을 {@code WorkspaceAccessChecker}로 제한합니다.
 *
 * <p>상수 선언을 분석하기 위해 common도 읽되 테스트 클래스는 제외합니다. mobile·signal·memory의 응답 통일은 이 검사 범위 밖의 후속 작업입니다.
 */
class WebForbiddenResponseBoundaryTests {

  private static final String WEB_PACKAGE = "works.momens.server.web";
  private static final String COMMON_PACKAGE = "works.momens.server.common";
  private static final String ACCESS_CHECKER = WEB_PACKAGE + ".WorkspaceAccessChecker";

  private final JavaClasses webClasses =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages(WEB_PACKAGE, COMMON_PACKAGE);

  @Test
  void onlyWorkspaceAccessCheckerBuildsForbiddenResponse() {
    noClasses()
        .that()
        .resideInAPackage(WEB_PACKAGE + "..")
        .and()
        .doNotHaveFullyQualifiedName(ACCESS_CHECKER)
        .should()
        .accessField(CommonErrorCode.class, "AUTH_FORBIDDEN")
        .check(webClasses);
  }
}
