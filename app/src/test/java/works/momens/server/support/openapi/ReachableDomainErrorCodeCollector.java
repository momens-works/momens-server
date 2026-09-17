package works.momens.server.support.openapi;

import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaMethod;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import works.momens.server.common.api.ErrorCode;

/**
 * 컨트롤러 메서드별로 호출 경로에서 도달할 수 있는 도메인 에러 코드를 수집합니다.
 *
 * <p>수집한 결과는 {@code @ApiException}에 선언된 도메인 에러 코드와 대조하는 데 사용합니다. 호출 경로의 탐색 규칙은 생성자로 전달받은 {@code
 * CallGraph}에 위임하므로 탐색 규칙이 변경되어도 이 클래스는 수정하지 않습니다.
 */
final class ReachableDomainErrorCodeCollector {

  private final CallGraph callGraph;

  ReachableDomainErrorCodeCollector(CallGraph callGraph) {
    this.callGraph = callGraph;
  }

  Map<JavaMethod, Set<ErrorCode>> collect(Collection<JavaMethod> handlerMethods) {
    return handlerMethods.stream()
        .collect(Collectors.toMap(Function.identity(), this::collectFrom));
  }

  /** 주어진 메서드나 생성자에서 호출 경로를 따라 도달할 수 있는 도메인 에러 코드를 반환합니다. */
  Set<ErrorCode> collectFrom(JavaCodeUnit start) {
    return callGraph.reachableFrom(start).stream()
        .flatMap(codeUnit -> DomainErrorCodes.referencedBy(codeUnit).stream())
        .collect(Collectors.toSet());
  }
}
