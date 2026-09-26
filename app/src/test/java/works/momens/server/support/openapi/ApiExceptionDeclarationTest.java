package works.momens.server.support.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import works.momens.server.common.api.ApiException;
import works.momens.server.common.api.ApiExceptions;

/**
 * {@code @ApiException}의 {@code codes}에 지정한 이름이 해당 enum에 실제로 존재하는지 검증합니다.
 *
 * <p>{@code codes}는 상수 이름을 문자열로 받기 때문에 컴파일러가 오타를 감지할 수 없습니다. 잘못된 이름은 OpenAPI 문서를 생성하는 과정에서 {@link
 * ApiExceptionResolver}가 예외를 던져 확인할 수 있지만, 이 경로를 검증하려면 Spring 컨텍스트와 Postgres 컨테이너를 기동하는 {@link
 * OpenApiSnapshotTest}를 실행해야 합니다. 단순한 오타를 확인하는 데 불필요한 비용이 발생하지 않도록 이 테스트는 동일한 검증을 Spring 컨텍스트 없이
 * 수행합니다.
 *
 * <p>애너테이션 해석에는 OpenAPI 문서 생성에 사용하는 것과 동일한 {@link ApiExceptionResolver}를 사용합니다. 문서 생성과 선언 검증에서
 * 애너테이션을 서로 다르게 해석해 한쪽만 통과하는 상황을 방지하기 위해서입니다.
 *
 * <p>ArchUnit은 반복 애너테이션의 컨테이너를 자동으로 해제하지 않습니다. 따라서 검사 대상 메서드를 찾을 때는 {@link ApiException}과 {@link
 * ApiExceptions}를 모두 확인합니다. 애너테이션 값을 읽을 때는 {@code reflect()}로 얻은 {@code Method}에서 표준 API인 {@code
 * getAnnotationsByType}을 사용합니다.
 */
class ApiExceptionDeclarationTest {

  private static final String BASE_PACKAGE = "works.momens.server";

  private static final JavaClasses CLASSES =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages(BASE_PACKAGE);

  private final ApiExceptionResolver apiExceptionResolver = new ApiExceptionResolver();

  @Test
  void everyDeclaredCodeExistsInItsEnum() {
    List<String> failures = new ArrayList<>();

    for (JavaMethod method : declaringMethods()) {
      try {
        apiExceptionResolver.resolve(method.reflect().getAnnotationsByType(ApiException.class));
      } catch (IllegalArgumentException e) {
        failures.add(method.getFullName() + " -> " + e.getMessage());
      }
    }

    assertThat(failures).isEmpty();
  }

  @Test
  void declaringMethodsAreFound() {
    assertThat(declaringMethods()).isNotEmpty();
  }

  private static List<JavaMethod> declaringMethods() {
    return CLASSES.stream()
        .map(JavaClass::getMethods)
        .flatMap(Set::stream)
        .filter(
            method ->
                method.isAnnotatedWith(ApiException.class)
                    || method.isAnnotatedWith(ApiExceptions.class))
        .toList();
  }
}
