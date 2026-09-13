package works.momens.server;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;
import works.momens.server.common.api.CommonErrorCode;

/**
 * {@code web} 모듈에서 권한 거부 응답을 생성하는 클래스가 하나인지 검증합니다(MOM-0885).
 *
 * <p>조합 서비스가 각각 {@code AUTH_FORBIDDEN}을 사용하면 {@code docs/spec/api-response-error-codes.md}의 「권한
 * details」 절에서 정한 응답 형식이 다시 분리될 수 있습니다. 이 형식은 {@code WorkspaceAccessChecker}가 관리하므로 해당 상수를 사용하는
 * 클래스도 하나여야 합니다. 컴파일러로 검증할 수 없는 규칙이므로 CI에서 확인합니다.
 *
 * <p>MOM-0919에서는 {@code web} 모듈이 {@code WorkspaceAccess} 타입을 직접 참조하지 않는지 검증했습니다. 그러나 이 규칙은 실제로
 * 제한하려는 권한 거부 응답 생성을 간접적으로 확인하는 방식이었습니다. 또한 MOM-0885에서 멤버십 조회 API를 하나로 통합하면서 멤버 목록 조회도 통합된 API를
 * 정상적으로 사용하게 되었으므로 더 이상 적용할 수 없습니다.
 *
 * <p>상수를 선언한 클래스까지 분석할 수 있도록 {@code common} 패키지도 함께 읽습니다. 검증 대상은 main 소스이므로 테스트 클래스는 제외합니다.
 *
 * <p>이 규칙은 {@code web} 모듈에만 적용합니다. {@code mobile}, {@code signal}, {@code memory} 모듈은 아직 동일한 응답 형식을
 * 사용하지 않으며, 해당 모듈의 정리는 별도 작업에서 다룹니다.
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
