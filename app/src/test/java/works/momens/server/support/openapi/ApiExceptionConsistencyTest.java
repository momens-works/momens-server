package works.momens.server.support.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import works.momens.server.common.api.ErrorCode;

/**
 * 컨트롤러 메서드별로 {@code @ApiException}에 선언된 도메인 에러 코드와 호출 경로에서 도달할 수 있는 도메인 에러 코드가 일치하는지 검증합니다.
 *
 * <p>선언에는 호출 경로에서 도달할 수 있는 도메인 에러 코드를 모두 포함해야 합니다. 호출 경로에서는 도달하지만 실제로 발생하지 않는 코드도 선언에서 제외하지 않습니다.
 * 실제 발생 여부는 DB 외래 키나 동시 요청처럼 코드 외부의 조건에 따라 달라져 테스트로 보장할 수 없기 때문입니다.
 *
 * <p>호출 경로를 탐색할 때는 {@code ExceptionCatchingTryBlocks}에 {@code TRANSLATES}로 등록된 {@code try} 블록 내부의
 * 호출을 제외합니다. 이 검사가 에러 코드를 누락하지 않는다는 것은 {@code ReachableDomainErrorCodeCollectorConditionTest}와
 * {@code ExceptionCatchingTryBlocksTest}가 모두 통과할 때만 보장됩니다.
 *
 * <p>선언은 OpenAPI 문서를 생성할 때와 동일하게 {@code ApiExceptionFinder}와 {@code ApiExceptionResolver}를 사용해
 * 읽습니다. {@code CommonErrorCode}는 대조 대상에서 제외합니다.
 */
class ApiExceptionConsistencyTest {

  private final JavaClasses classes = MainSourceClasses.imported();

  private final ReachableDomainErrorCodeCollector collector =
      new ReachableDomainErrorCodeCollector(
          new CallGraph(
              classes, access -> !ExceptionCatchingTryBlocks.isInTranslatingTryBlock(access)));

  private final ApiExceptionFinder apiExceptionFinder = new ApiExceptionFinder();

  private final ApiExceptionResolver apiExceptionResolver = new ApiExceptionResolver();

  @Test
  void declaredDomainErrorCodesMatchReachableDomainErrorCodes() {
    List<String> mismatches =
        collector.collect(HandlerMethods.declaredIn(classes)).entrySet().stream()
            .map(this::mismatchOf)
            .flatMap(Optional::stream)
            .sorted()
            .toList();

    assertThat(mismatches)
        .as(
            "컨트롤러 메서드의 @ApiException 선언이 호출 경로에서 도달하는 도메인 에러 코드와 일치하지 않습니다. undeclared는 선언에 추가해야 하는 코드이고, unreachable은 호출 경로에서 도달하지 않아 선언에서 제거해야 하는 코드입니다. 선언을 수정한 뒤 ./gradlew updateOpenApiSnapshot을 실행해 OpenAPI 스냅샷을 다시 생성하세요.")
        .isEmpty();
  }

  private Optional<String> mismatchOf(Map.Entry<JavaMethod, Set<ErrorCode>> reachableByHandler) {
    JavaMethod handlerMethod = reachableByHandler.getKey();
    Set<String> reachable = codesOf(reachableByHandler.getValue());
    Set<String> declared = codesOf(declaredDomainErrorCodes(handlerMethod));
    Set<String> undeclared = difference(reachable, declared);
    Set<String> unreachable = difference(declared, reachable);
    if (undeclared.isEmpty() && unreachable.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(
        HandlerMethods.operationIdOf(handlerMethod)
            + " undeclared="
            + undeclared
            + " unreachable="
            + unreachable);
  }

  private List<ErrorCode> declaredDomainErrorCodes(JavaMethod handlerMethod) {
    return apiExceptionResolver
        .resolve(
            apiExceptionFinder.find(handlerMethod.getOwner().reflect(), handlerMethod.reflect()))
        .stream()
        .filter(DomainErrorCodes::isDomainErrorCode)
        .toList();
  }

  private static Set<String> codesOf(Iterable<? extends ErrorCode> errorCodes) {
    Set<String> codes = new TreeSet<>();
    errorCodes.forEach(errorCode -> codes.add(errorCode.code()));
    return codes;
  }

  private static Set<String> difference(Set<String> left, Set<String> right) {
    return left.stream()
        .filter(code -> !right.contains(code))
        .collect(Collectors.toCollection(TreeSet::new));
  }
}
