package works.momens.server.support.openapi;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import works.momens.server.common.api.ApiException;

/**
 * 컨트롤러 메서드에 적용되는 {@code @ApiException} 선언을 찾습니다.
 *
 * <p>컨트롤러 메서드에 선언이 있으면 해당 선언을 사용합니다. 선언이 없으면 컨트롤러가 구현한 인터페이스에서 시그니처가 같은 메서드의 선언을 찾습니다.
 *
 * <p>OpenAPI 문서를 생성하는 {@code SwaggerOperationCustomizer}와 선언을 검증하는 테스트가 같은 선언을 읽도록 탐색 규칙을 이 클래스에서
 * 일괄 관리합니다.
 */
public class ApiExceptionFinder {

  public ApiException[] find(Class<?> beanType, Method method) {
    ApiException[] apiExceptions = method.getAnnotationsByType(ApiException.class);
    if (apiExceptions.length > 0) {
      return apiExceptions;
    }

    return interfaceTypes(beanType).stream()
        .map(interfaceType -> findInterfaceMethod(interfaceType, method))
        .filter(interfaceMethod -> interfaceMethod != null)
        .map(interfaceMethod -> interfaceMethod.getAnnotationsByType(ApiException.class))
        .filter(annotations -> annotations.length > 0)
        .findFirst()
        .orElse(new ApiException[0]);
  }

  private Set<Class<?>> interfaceTypes(Class<?> beanType) {
    Set<Class<?>> interfaceTypes = new LinkedHashSet<>();
    Class<?> currentType = beanType;
    while (currentType != null && currentType != Object.class) {
      collectInterfaces(currentType, interfaceTypes);
      currentType = currentType.getSuperclass();
    }
    return interfaceTypes;
  }

  private void collectInterfaces(Class<?> type, Set<Class<?>> interfaceTypes) {
    for (Class<?> interfaceType : type.getInterfaces()) {
      if (interfaceTypes.add(interfaceType)) {
        collectInterfaces(interfaceType, interfaceTypes);
      }
    }
  }

  private Method findInterfaceMethod(Class<?> interfaceType, Method targetMethod) {
    return Arrays.stream(interfaceType.getMethods())
        .filter(interfaceMethod -> hasSameSignature(interfaceMethod, targetMethod))
        .findFirst()
        .orElse(null);
  }

  private boolean hasSameSignature(Method interfaceMethod, Method targetMethod) {
    return interfaceMethod.getName().equals(targetMethod.getName())
        && Arrays.equals(interfaceMethod.getParameterTypes(), targetMethod.getParameterTypes());
  }
}
