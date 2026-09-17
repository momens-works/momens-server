package works.momens.server.support.openapi;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Spring MVC가 요청 처리 메서드로 등록하는 컨트롤러 메서드를 찾습니다.
 *
 * <p>Spring의 {@code RequestMappingHandlerMapping}과 같은 기준을 적용합니다. {@code @Controller}를 메타 애너테이션으로 갖는
 * 애너테이션이 적용된 클래스에서 {@code @RequestMapping}을 메타 애너테이션으로 갖는 애너테이션이 적용된 메서드를 찾습니다. 따라서
 * {@code @RestController} 클래스의 {@code @GetMapping}, {@code @PostMapping} 메서드도 포함됩니다. 인터페이스 메서드에 선언된
 * 매핑 애너테이션은 탐색하지 않습니다.
 */
final class HandlerMethods {

  private HandlerMethods() {}

  static List<JavaMethod> declaredIn(JavaClasses classes) {
    return classes.stream()
        .filter(javaClass -> javaClass.isMetaAnnotatedWith(Controller.class))
        .flatMap(javaClass -> javaClass.getMethods().stream())
        .filter(method -> method.isMetaAnnotatedWith(RequestMapping.class))
        .toList();
  }

  /**
   * 컨트롤러 메서드의 {@code operationId}를 반환합니다.
   *
   * <p>컨트롤러가 구현한 인터페이스의 동일한 시그니처를 가진 메서드에 선언된 {@code @Operation}까지 탐색합니다. {@code @Operation}이 없으면
   * 메서드의 전체 이름을 반환합니다.
   */
  static String operationIdOf(JavaMethod handlerMethod) {
    Operation operation =
        AnnotatedElementUtils.findMergedAnnotation(handlerMethod.reflect(), Operation.class);
    return operation == null ? handlerMethod.getFullName() : operation.operationId();
  }
}
