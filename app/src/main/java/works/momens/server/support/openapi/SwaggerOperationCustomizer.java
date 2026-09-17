package works.momens.server.support.openapi;

import io.swagger.v3.oas.models.Operation;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.web.method.HandlerMethod;
import works.momens.server.common.api.ApiException;

/** 컨트롤러 메서드 또는 docs interface에 선언된 {@link ApiException}을 읽어 OpenAPI 실패 응답 예시를 추가합니다. */
@RequiredArgsConstructor
public class SwaggerOperationCustomizer implements OperationCustomizer {

  private final ApiExceptionFinder apiExceptionFinder;

  private final ApiExceptionResolver apiExceptionResolver;

  private final SwaggerErrorExampleGenerator swaggerErrorExampleGenerator;

  @Override
  public Operation customize(Operation operation, HandlerMethod handlerMethod) {
    ApiException[] apiExceptions =
        apiExceptionFinder.find(handlerMethod.getBeanType(), handlerMethod.getMethod());
    if (apiExceptions.length > 0) {
      swaggerErrorExampleGenerator.addErrorResponses(
          operation, apiExceptionResolver.resolve(apiExceptions));
    }
    return operation;
  }
}
