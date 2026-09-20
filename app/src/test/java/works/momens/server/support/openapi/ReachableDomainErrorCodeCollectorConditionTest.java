package works.momens.server.support.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaMethod;
import java.io.IOException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import works.momens.server.common.api.BusinessException;
import works.momens.server.common.api.ErrorCode;

/**
 * {@code ReachableDomainErrorCodeCollector}가 도메인 에러 코드를 누락 없이 수집하는 데 필요한 조건이 현재 코드에서 지켜지는지 검증합니다.
 *
 * <p>수집기는 컨트롤러 메서드에서 탐색을 시작해 애플리케이션 코드의 메서드 호출과 메서드 참조만 따라갑니다. 메서드 본문에서 enum 상수를 참조하는 지점을 에러 코드가
 * 발생할 수 있는 지점으로 판단합니다. 따라서 다음 조건 중 하나라도 어긋나면 수집 결과에서 에러 코드가 누락될 수 있습니다. 이 테스트가 통과할 때만 수집 결과에 누락이
 * 없음을 보장합니다.
 *
 * <ul>
 *   <li>탐색을 시작하는 컨트롤러 메서드의 {@code operationId} 집합이 OpenAPI 스냅샷과 일치해야 합니다.
 *   <li>도메인 에러 코드 enum 상수는 메서드 본문에서만 참조하고, {@code values()}나 {@code valueOf()}로 조회하지 않아야 합니다.
 *   <li>{@code BusinessException} 외에는 도메인 에러 코드를 필드에 저장하지 않아야 합니다.
 *   <li>애플리케이션 코드가 호출하지 않는 메서드에서는 도메인 에러 코드에 도달하지 않아야 합니다. 이벤트 리스너나 AOP처럼 프레임워크가 호출하는 경로는 탐색 대상이
 *       아니기 때문입니다.
 *   <li>도메인 에러 코드를 생성하는 클래스는 외부 라이브러리의 타입을 구현하거나 상속하지 않아야 합니다. 외부 라이브러리가 호출하는 메서드는 탐색 대상이 아니기
 *       때문입니다.
 * </ul>
 *
 * <p>ArchUnit이 람다, 메서드 참조, 브리지 메서드, 익명 클래스를 해석하는 방식은 {@code CallGraphTest}에서 검증합니다.
 */
class ReachableDomainErrorCodeCollectorConditionTest {

  private final JavaClasses classes = MainSourceClasses.imported();

  @Test
  void handlerMethodOperationIdsMatchSnapshot() throws IOException {
    Set<String> handlerOperationIds =
        HandlerMethods.declaredIn(classes).stream()
            .map(HandlerMethods::operationIdOf)
            .collect(Collectors.toCollection(TreeSet::new));

    assertThat(handlerOperationIds)
        .as(
            "컨트롤러 메서드의 operationId 집합이 OpenAPI 스냅샷과 일치하지 않습니다. 매핑 애너테이션이 인터페이스 메서드에만 선언되어 있거나 Spring MVC가 @Controller로 인식하지 못하는 컨트롤러가 있는지 확인하세요.")
        .isEqualTo(snapshotOperationIds());
  }

  @Test
  void domainErrorCodeConstantsAreReferencedOnlyInMethods() {
    List<String> violations =
        allCodeUnits()
            .filter(codeUnit -> !DomainErrorCodes.isDomainErrorCodeEnum(codeUnit.getOwner()))
            .flatMap(
                codeUnit ->
                    Stream.concat(
                        codeUnit.getFieldAccesses().stream()
                            .filter(
                                access ->
                                    DomainErrorCodes.isDomainErrorCodeEnum(access.getTargetOwner()))
                            .filter(access -> !(codeUnit instanceof JavaMethod))
                            .map(access -> access.getDescription()),
                        codeUnit.getMethodCallsFromSelf().stream()
                            .filter(
                                call ->
                                    DomainErrorCodes.isDomainErrorCodeEnum(call.getTargetOwner()))
                            .filter(
                                call ->
                                    call.getTarget().getName().equals("values")
                                        || call.getTarget().getName().equals("valueOf"))
                            .map(call -> call.getDescription())))
            .toList();

    assertThat(violations)
        .as(
            "도메인 에러 코드 enum 상수를 메서드 본문 밖에서 참조하거나 values(), valueOf()로 조회하는 코드가 있습니다. 호출 경로 탐색으로 해당 에러 코드를 찾을 수 없으므로 메서드 본문에서 enum 상수를 직접 참조하도록 수정하세요.")
        .isEmpty();
  }

  @Test
  void onlyBusinessExceptionHoldsDomainErrorCodeInField() {
    List<String> violations =
        classes.stream()
            .filter(javaClass -> !DomainErrorCodes.isDomainErrorCodeEnum(javaClass))
            .filter(javaClass -> !javaClass.isEquivalentTo(BusinessException.class))
            .flatMap(javaClass -> javaClass.getFields().stream())
            .filter(
                field ->
                    field.getRawType().isEquivalentTo(ErrorCode.class)
                        || DomainErrorCodes.isDomainErrorCodeEnum(field.getRawType()))
            .map(field -> field.getFullName())
            .toList();

    assertThat(violations)
        .as(
            "BusinessException 외의 클래스가 도메인 에러 코드를 필드에 저장하고 있습니다. 호출 경로 탐색으로 해당 에러 코드가 발생하는 위치를 찾을 수 없으므로 메서드 본문에서 enum 상수를 직접 참조하도록 수정하세요.")
        .isEmpty();
  }

  @Test
  void codeUnitsWithoutCallersDoNotReachDomainErrorCodes() {
    CallGraph callGraph = new CallGraph(classes);
    ReachableDomainErrorCodeCollector collector = new ReachableDomainErrorCodeCollector(callGraph);
    Set<JavaCodeUnit> handlerMethods = Collections.newSetFromMap(new IdentityHashMap<>());
    handlerMethods.addAll(HandlerMethods.declaredIn(classes));

    Map<String, Set<ErrorCode>> violations =
        callGraph.codeUnitsWithoutCallers().stream()
            .filter(codeUnit -> !handlerMethods.contains(codeUnit))
            .map(codeUnit -> Map.entry(codeUnit.getFullName(), collector.collectFrom(codeUnit)))
            .filter(entry -> !entry.getValue().isEmpty())
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

    assertThat(violations)
        .as(
            "애플리케이션 코드가 호출하지 않는 메서드에서 도메인 에러 코드에 도달합니다. 프레임워크가 호출하는 경로는 호출 경로 탐색에 포함되지 않으므로 해당 에러 코드가 @ApiException 선언과의 대조에서 누락됩니다.")
        .isEmpty();
  }

  @Test
  void classesReferencingDomainErrorCodesDoNotImplementExternalTypes() {
    List<String> violations =
        allCodeUnits()
            .filter(codeUnit -> !DomainErrorCodes.referencedBy(codeUnit).isEmpty())
            .map(JavaCodeUnit::getOwner)
            .distinct()
            .flatMap(
                owner ->
                    Stream.concat(
                            owner.getAllRawInterfaces().stream(),
                            owner.getAllRawSuperclasses().stream())
                        .filter(type -> !type.isEquivalentTo(Object.class))
                        .filter(type -> !classes.contain(type.getName()))
                        .map(type -> owner.getName() + " -> " + type.getName()))
            .toList();

    assertThat(violations)
        .as(
            "도메인 에러 코드를 생성하는 클래스가 외부 라이브러리의 타입을 구현하거나 상속하고 있습니다. 외부 라이브러리가 호출하는 메서드는 호출 경로 탐색에 포함되지 않으므로 해당 클래스에서 발생하는 에러 코드가 누락될 수 있습니다.")
        .isEmpty();
  }

  private Stream<JavaCodeUnit> allCodeUnits() {
    return classes.stream().flatMap(javaClass -> javaClass.getCodeUnits().stream());
  }

  private static Set<String> snapshotOperationIds() throws IOException {
    return OpenApiSnapshot.operations().stream()
        .map(OpenApiOperation::operationId)
        .collect(Collectors.toCollection(TreeSet::new));
  }
}
