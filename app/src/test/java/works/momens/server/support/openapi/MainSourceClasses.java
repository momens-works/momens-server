package works.momens.server.support.openapi;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

/**
 * main 소스의 클래스를 ArchUnit으로 한 번만 가져와 여러 테스트에서 공유하도록 제공합니다.
 *
 * <p>테스트 소스와 {@code testFixtures}의 클래스는 가져오지 않습니다. {@code testFixtures}에는 main 소스의 인터페이스를 구현한 테스트용
 * 클래스가 있으므로, 함께 가져오면 운영 코드에서 실행되지 않는 구현이 호출 경로 탐색 결과에 포함됩니다.
 *
 * <p>클래스를 가져오는 범위를 이 클래스에서 일괄 관리해 호출 경로를 탐색하는 테스트마다 분석 범위가 달라지는 것을 방지합니다.
 */
final class MainSourceClasses {

  private static final String BASE_PACKAGE = "works.momens.server";

  private static final JavaClasses IMPORTED =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TEST_FIXTURES)
          .importPackages(BASE_PACKAGE);

  private MainSourceClasses() {}

  static JavaClasses imported() {
    return IMPORTED;
  }
}
