package works.momens.server.support.openapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;
import works.momens.server.common.api.ApiException;
import works.momens.server.common.api.CommonErrorCode;
import works.momens.server.common.api.ErrorCode;

/**
 * {@link ApiExceptionResolver}가 애너테이션 선언을 {@link ErrorCode} 목록으로 변환하는 규칙을 검증합니다.
 *
 * <p>다음 세 가지를 확인합니다.
 *
 * <ul>
 *   <li>{@code codes}에 지정한 코드만 선택하는지 검증합니다.
 *   <li>{@code codes}가 비어 있으면 해당 enum의 모든 상수를 반환하는지 검증합니다.
 *   <li>해당 enum에 없는 코드를 지정하면 예외를 던지는지 검증합니다.
 * </ul>
 *
 * <p>애너테이션 인스턴스는 직접 생성할 수 없으므로, 테스트용 애너테이션 선언을 포함한 중첩 인터페이스를 정의하고 리플렉션으로 값을 읽어 검증합니다.
 */
class ApiExceptionResolverTest {

  private final ApiExceptionResolver apiExceptionResolver = new ApiExceptionResolver();

  @Test
  void resolvesOnlyDeclaredCodes() {
    assertThat(resolve("partial"))
        .containsExactly(CommonErrorCode.COMMON_NOT_FOUND, CommonErrorCode.AUTH_FORBIDDEN);
  }

  @Test
  void resolvesEveryConstantWhenCodesAreEmpty() {
    assertThat(resolve("all")).containsExactly(CommonErrorCode.values());
  }

  @Test
  void rejectsCodeThatDoesNotExistInEnum() {
    assertThatThrownBy(() -> resolve("unknown"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("COMMON_NO_SUCH_CODE");
  }

  private List<ErrorCode> resolve(String methodName) {
    return apiExceptionResolver.resolve(declarationsOf(methodName));
  }

  private static ApiException[] declarationsOf(String methodName) {
    try {
      Method method = Declarations.class.getDeclaredMethod(methodName);
      return method.getAnnotationsByType(ApiException.class);
    } catch (NoSuchMethodException e) {
      throw new IllegalStateException(e);
    }
  }

  private interface Declarations {

    @ApiException(
        value = CommonErrorCode.class,
        codes = {"COMMON_NOT_FOUND", "AUTH_FORBIDDEN"})
    void partial();

    @ApiException(CommonErrorCode.class)
    void all();

    @ApiException(
        value = CommonErrorCode.class,
        codes = {"COMMON_NO_SUCH_CODE"})
    void unknown();
  }
}
