package works.momens.server.support.openapi;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import works.momens.server.common.api.ApiException;
import works.momens.server.common.api.ErrorCode;

/**
 * {@link ApiException} 선언을 문서화할 {@link ErrorCode} 목록으로 변환합니다. 지정한 클래스가 enum이 아니거나 해당 enum에 없는 코드를
 * 지정하면 예외를 던집니다. OpenAPI 문서 생성과 선언 검증 테스트가 동일한 방식으로 애너테이션을 해석하도록 변환 규칙을 이 클래스에서 관리합니다.
 */
public class ApiExceptionResolver {

  public List<ErrorCode> resolve(ApiException[] apiExceptions) {
    List<ErrorCode> errorCodes = new ArrayList<>();
    for (ApiException apiException : apiExceptions) {
      errorCodes.addAll(resolveOne(apiException));
    }
    return errorCodes;
  }

  private List<ErrorCode> resolveOne(ApiException apiException) {
    Class<? extends ErrorCode> errorEnum = apiException.value();
    ErrorCode[] constants = enumConstants(errorEnum);
    String[] codes = apiException.codes();
    if (codes.length == 0) {
      return Arrays.asList(constants);
    }
    return Arrays.stream(codes).map(code -> findByCode(constants, code, errorEnum)).toList();
  }

  private ErrorCode[] enumConstants(Class<? extends ErrorCode> errorEnum) {
    ErrorCode[] constants = errorEnum.getEnumConstants();
    if (constants == null) {
      throw new IllegalArgumentException(
          "@ApiException only supports ErrorCode enum classes: " + errorEnum.getName());
    }
    return constants;
  }

  private ErrorCode findByCode(
      ErrorCode[] constants, String code, Class<? extends ErrorCode> errorEnum) {
    return Arrays.stream(constants)
        .filter(constant -> constant.code().equals(code))
        .findFirst()
        .orElseThrow(() ->
            new IllegalArgumentException(
                "@ApiException declares a code that does not exist in "
                    + errorEnum.getName()
                    + ": "
                    + code));
  }
}
