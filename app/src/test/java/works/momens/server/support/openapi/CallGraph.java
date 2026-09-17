package works.momens.server.support.openapi;

import com.tngtech.archunit.core.domain.AccessTarget.CodeUnitAccessTarget;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaCodeUnitAccess;
import com.tngtech.archunit.core.domain.JavaConstructor;
import com.tngtech.archunit.core.domain.JavaModifier;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * 주어진 메서드에서 탐색을 시작해 호출 경로에서 도달할 수 있는 메서드와 생성자를 모두 찾습니다.
 *
 * <p>ArchUnit이 바이트코드에서 읽은 메서드 호출과 메서드 참조를 따라가며, 호출 대상은 CHA(Class Hierarchy Analysis) 규칙에 따라 연결합니다.
 * 호출 지점에 선언된 수신 객체 타입과 그 하위 타입에서 동일한 시그니처를 가진 메서드 및 상속받은 구현을 호출 대상으로 봅니다. 따라서 인터페이스를 통한 호출은 모든 구현
 * 클래스로 이어지며, 제네릭 타입을 구현할 때 컴파일러가 생성하는 브리지 메서드도 함께 탐색합니다.
 *
 * <p>생성자와 {@code static} 메서드, {@code private} 메서드는 JVM이 호출 대상을 정적으로 결정하므로 해석된 메서드 하나로만 연결합니다.
 *
 * <p>ArchUnit은 람다 본문의 호출을 람다가 선언된 메서드의 호출로 기록하므로 람다는 별도로 처리하지 않습니다. 익명 클래스의 메서드는 외부 라이브러리에서 호출될 수
 * 있으므로, 익명 클래스를 생성하는 메서드에서는 해당 익명 클래스의 모든 메서드까지 탐색합니다.
 *
 * <p>탐색 범위는 생성자로 전달받은 {@code JavaClasses}에 포함된 클래스로 한정합니다. 실행 시 선택되지 않는 구현이 탐색 결과에 포함될 수 있지만, 실행
 * 가능한 호출 경로를 누락하지 않는 것을 우선합니다.
 */
final class CallGraph {

  private final JavaClasses classes;
  private final Predicate<JavaAccess<?>> followedAccess;

  CallGraph(JavaClasses classes) {
    this(classes, access -> true);
  }

  /**
   * 탐색 범위와 탐색 중 따라갈 호출을 지정해 호출 그래프를 생성합니다.
   *
   * <p>{@code followedAccess}가 {@code false}를 반환하는 메서드 호출과 메서드 참조는 탐색하지 않습니다. 같은 조건은 {@link
   * #codeUnitsWithoutCallers()}에서 호출 관계를 판단할 때도 적용됩니다.
   */
  CallGraph(JavaClasses classes, Predicate<JavaAccess<?>> followedAccess) {
    this.classes = classes;
    this.followedAccess = followedAccess;
  }

  Set<JavaCodeUnit> reachableFrom(JavaCodeUnit start) {
    return reachableFromAll(Stream.of(start));
  }

  /** 주어진 메서드 호출이나 메서드 참조가 가리키는 호출 대상에서 탐색을 시작해 도달할 수 있는 메서드와 생성자를 반환합니다. */
  Set<JavaCodeUnit> reachableThrough(JavaCodeUnitAccess<?> access) {
    return reachableFromAll(dispatchTargets(access.getTarget()));
  }

  private Set<JavaCodeUnit> reachableFromAll(Stream<JavaCodeUnit> starts) {
    Set<JavaCodeUnit> reached = Collections.newSetFromMap(new IdentityHashMap<>());
    Deque<JavaCodeUnit> pending = new ArrayDeque<>();
    starts.forEach(pending::push);
    while (!pending.isEmpty()) {
      JavaCodeUnit current = pending.pop();
      if (reached.add(current)) {
        callees(current).forEach(pending::push);
      }
    }
    return reached;
  }

  /**
   * 가져온 클래스 중 다른 코드에서 호출하거나 참조하지 않는 메서드와 생성자를 반환합니다.
   *
   * <p>호출 관계는 {@link #reachableFrom(JavaCodeUnit)}과 같은 규칙으로 판단합니다. 컨트롤러 메서드, 이벤트 리스너, 스케줄러 메서드처럼
   * 애플리케이션 코드가 직접 호출하지 않고 프레임워크가 호출하는 코드가 결과에 포함됩니다.
   */
  Set<JavaCodeUnit> codeUnitsWithoutCallers() {
    Set<JavaCodeUnit> called = Collections.newSetFromMap(new IdentityHashMap<>());
    allCodeUnits().flatMap(this::callees).forEach(called::add);
    Set<JavaCodeUnit> uncalled = Collections.newSetFromMap(new IdentityHashMap<>());
    allCodeUnits().filter(codeUnit -> !called.contains(codeUnit)).forEach(uncalled::add);
    return uncalled;
  }

  private Stream<JavaCodeUnit> allCodeUnits() {
    return classes.stream().flatMap(javaClass -> javaClass.getCodeUnits().stream());
  }

  private Stream<JavaCodeUnit> callees(JavaCodeUnit caller) {
    Stream<CodeUnitAccessTarget> calls =
        caller.getCallsFromSelf().stream().filter(followedAccess).map(call -> call.getTarget());
    Stream<CodeUnitAccessTarget> references =
        caller.getCodeUnitReferencesFromSelf().stream()
            .filter(followedAccess)
            .map(reference -> reference.getTarget());
    return Stream.concat(calls, references).flatMap(this::dispatchTargets);
  }

  private Stream<JavaCodeUnit> dispatchTargets(CodeUnitAccessTarget target) {
    if (!isImported(target.getOwner())) {
      return Stream.empty();
    }
    Optional<? extends JavaCodeUnit> resolved = target.resolveMember();
    Stream<JavaCodeUnit> methods =
        resolved.isPresent() && isStaticallyBound(resolved.get())
            ? Stream.<JavaCodeUnit>of(resolved.get())
                .filter(codeUnit -> isImported(codeUnit.getOwner()))
            : classHierarchyTargets(target, resolved);
    return Stream.concat(methods, anonymousClassCodeUnits(target));
  }

  private Stream<JavaCodeUnit> classHierarchyTargets(
      CodeUnitAccessTarget target, Optional<? extends JavaCodeUnit> resolved) {
    JavaClass receiverType = target.getOwner();
    JavaClass declaringClass = resolved.map(JavaCodeUnit::getOwner).orElse(receiverType);
    List<String> parameterTypeNames = rawParameterTypeNames(target.getRawParameterTypes());
    return Stream.concat(
            Stream.of(declaringClass, receiverType), receiverType.getAllSubclasses().stream())
        .distinct()
        .filter(this::isImported)
        .flatMap(javaClass -> javaClass.getCodeUnits().stream())
        .filter(
            codeUnit ->
                codeUnit.getName().equals(target.getName())
                    && rawParameterTypeNames(codeUnit.getRawParameterTypes())
                        .equals(parameterTypeNames));
  }

  private boolean isImported(JavaClass javaClass) {
    return classes.contain(javaClass.getName());
  }

  private static boolean isStaticallyBound(JavaCodeUnit codeUnit) {
    return codeUnit instanceof JavaConstructor
        || codeUnit.getModifiers().contains(JavaModifier.STATIC)
        || codeUnit.getModifiers().contains(JavaModifier.PRIVATE);
  }

  private static Stream<JavaCodeUnit> anonymousClassCodeUnits(CodeUnitAccessTarget target) {
    JavaClass owner = target.getOwner();
    if (owner.isAnonymousClass() && target.getName().equals(JavaConstructor.CONSTRUCTOR_NAME)) {
      return owner.getCodeUnits().stream();
    }
    return Stream.empty();
  }

  private static List<String> rawParameterTypeNames(List<JavaClass> rawParameterTypes) {
    return rawParameterTypes.stream().map(JavaClass::getName).toList();
  }
}
