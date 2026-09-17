package works.momens.server.support.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import works.momens.server.common.api.BusinessException;
import works.momens.server.common.api.ErrorCode;

/**
 * {@code CallGraph}가 여러 호출 형태에서도 에러 코드를 생성하는 메서드까지 올바르게 연결하는지 검증합니다.
 *
 * <p>ArchUnit의 바이트코드 해석 방식에 따라 탐색 결과가 달라질 수 있는 호출 형태를 검증합니다. 람다 본문의 호출, 자기 자신의 메서드를 참조하는 메서드 참조,
 * 제네릭 인터페이스 호출에서 생성되는 브리지 메서드, 하위 클래스 타입으로 호출한 상속 메서드, 외부 라이브러리에 전달한 익명 클래스를 대상으로 합니다. ArchUnit 버전을
 * 올린 뒤 바이트코드 해석 방식이 달라지면 이 테스트가 실패합니다.
 *
 * <p>검증에 사용하는 클래스는 이 테스트의 중첩 클래스로 선언하고, 이 테스트 클래스의 이름으로 시작하는 클래스만 가져옵니다.
 */
class CallGraphTest {

  private static final JavaClasses FIXTURE_CLASSES =
      new ClassFileImporter()
          .withImportOption(location -> location.contains("/CallGraphTest$"))
          .importPackagesOf(CallGraphTest.class);

  private final ReachableDomainErrorCodeCollector collector =
      new ReachableDomainErrorCodeCollector(new CallGraph(FIXTURE_CLASSES));

  @ParameterizedTest
  @CsvSource({
    "callsHelperInLambda, LAMBDA",
    "referencesOwnMethod, METHOD_REFERENCE",
    "callsGenericInterface, BRIDGE_METHOD",
    "callsInheritedMethod, INHERITED_METHOD",
    "passesAnonymousClassToLibrary, ANONYMOUS_CLASS"
  })
  void reachesErrorCodeThroughCallShape(String entryMethodName, FixtureErrorCode expected) {
    var entryMethod =
        FIXTURE_CLASSES.get(Fixture.class).getCodeUnits().stream()
            .filter(codeUnit -> codeUnit.getName().equals(entryMethodName))
            .findFirst()
            .orElseThrow();

    assertThat(collector.collectFrom(entryMethod)).containsExactly(expected);
  }

  enum FixtureErrorCode implements ErrorCode {
    LAMBDA,
    METHOD_REFERENCE,
    BRIDGE_METHOD,
    INHERITED_METHOD,
    ANONYMOUS_CLASS;

    @Override
    public String code() {
      return name();
    }

    @Override
    public int status() {
      return 400;
    }

    @Override
    public String defaultMessage() {
      return name();
    }
  }

  static final class Fixture {

    private final Converter<String> converter = new ThrowingConverter();

    void callsHelperInLambda() {
      Optional.empty().orElseThrow(() -> lambdaFailure());
    }

    void referencesOwnMethod() {
      Optional.empty().orElseThrow(this::methodReferenceFailure);
    }

    void callsGenericInterface() {
      converter.convert("value");
    }

    void callsInheritedMethod(ThrowingTemplate template) {
      template.run();
    }

    void passesAnonymousClassToLibrary() {
      Optional.empty()
          .orElseThrow(
              new Supplier<BusinessException>() {
                @Override
                public BusinessException get() {
                  return new BusinessException(FixtureErrorCode.ANONYMOUS_CLASS);
                }
              });
    }

    private static BusinessException lambdaFailure() {
      return new BusinessException(FixtureErrorCode.LAMBDA);
    }

    private BusinessException methodReferenceFailure() {
      return new BusinessException(FixtureErrorCode.METHOD_REFERENCE);
    }
  }

  interface Converter<T> {
    T convert(T value);
  }

  static final class ThrowingConverter implements Converter<String> {
    @Override
    public String convert(String value) {
      throw new BusinessException(FixtureErrorCode.BRIDGE_METHOD);
    }
  }

  abstract static class Template {
    final void run() {
      hook();
    }

    abstract void hook();
  }

  static final class ThrowingTemplate extends Template {
    @Override
    void hook() {
      throw new BusinessException(FixtureErrorCode.INHERITED_METHOD);
    }
  }
}
