package works.momens.server.support.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.models.Operation;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.web.method.HandlerMethod;
import works.momens.server.common.api.ApiException;
import works.momens.server.common.api.CommonErrorCode;

class SwaggerOperationCustomizerTest {

  private final SwaggerOperationCustomizer customizer =
      new SwaggerOperationCustomizer(
          new ApiExceptionFinder(), new ApiExceptionResolver(), new SwaggerErrorExampleGenerator());

  @Test
  void readsApiExceptionsFromControllerDocsInterface() throws NoSuchMethodException {
    InterfaceAnnotatedController controller = new InterfaceAnnotatedController();
    Method method = InterfaceAnnotatedController.class.getMethod("get");
    HandlerMethod handlerMethod = new HandlerMethod(controller, method);

    Operation operation = customizer.customize(new Operation(), handlerMethod);

    assertThat(
            operation.getResponses().get("401").getContent().get("application/json").getExamples())
        .containsKey("AUTH_UNAUTHORIZED");
  }

  @Test
  void readsApiExceptionsFromControllerMethod() throws NoSuchMethodException {
    MethodAnnotatedController controller = new MethodAnnotatedController();
    Method method = MethodAnnotatedController.class.getMethod("get");
    HandlerMethod handlerMethod = new HandlerMethod(controller, method);

    Operation operation = customizer.customize(new Operation(), handlerMethod);

    assertThat(
            operation.getResponses().get("404").getContent().get("application/json").getExamples())
        .containsKey("COMMON_NOT_FOUND");
  }

  @Test
  void readsApiExceptionsFromInheritedControllerDocsInterface() throws NoSuchMethodException {
    InheritedInterfaceController controller = new InheritedInterfaceController();
    Method method = InheritedInterfaceController.class.getMethod("get");
    HandlerMethod handlerMethod = new HandlerMethod(controller, method);

    Operation operation = customizer.customize(new Operation(), handlerMethod);

    assertThat(
            operation.getResponses().get("401").getContent().get("application/json").getExamples())
        .containsKey("AUTH_UNAUTHORIZED");
  }

  private interface InterfaceAnnotatedControllerDocs {

    @ApiException(CommonErrorCode.class)
    void get();
  }

  private static class InterfaceAnnotatedController implements InterfaceAnnotatedControllerDocs {

    @Override
    public void get() {}
  }

  private static class MethodAnnotatedController {

    @ApiException(CommonErrorCode.class)
    public void get() {}
  }

  private interface InheritedControllerDocs extends InterfaceAnnotatedControllerDocs {}

  private abstract static class AbstractInheritedInterfaceController
      implements InheritedControllerDocs {}

  private static class InheritedInterfaceController extends AbstractInheritedInterfaceController {

    @Override
    public void get() {}
  }
}
