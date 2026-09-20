package works.momens.server.support.openapi;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 애플리케이션 코드가 아닌 프레임워크가 호출하는 코드 단위를 반환합니다.
 *
 * <p>두 가지 기준을 함께 적용합니다. 첫 번째는 Call graph에서 애플리케이션 코드의 호출로 도달할 수 없는 코드 단위이고, 두 번째는 프레임워크의 진입 지점임을
 * 애너테이션으로 나타낸 메서드입니다. 애너테이션이 붙은 메서드를 애플리케이션 코드에서도 호출하면 첫 번째 기준만으로는 찾을 수 없고, 목록에 없는 애너테이션은 두 번째 기준으로
 * 찾을 수 없으므로 두 기준을 함께 둡니다.
 *
 * <p>애너테이션은 메타 애너테이션까지 확인합니다.
 */
final class FrameworkInvokedCodeUnits {

  private static final List<String> FRAMEWORK_INVOCATION_ANNOTATIONS =
      List.of(
          "org.springframework.scheduling.annotation.Scheduled",
          "org.springframework.context.event.EventListener",
          "jakarta.annotation.PostConstruct",
          "jakarta.annotation.PreDestroy");

  private FrameworkInvokedCodeUnits() {}

  static Set<JavaCodeUnit> in(JavaClasses classes, CallGraph callGraph) {
    Set<JavaCodeUnit> codeUnits = Collections.newSetFromMap(new IdentityHashMap<>());
    codeUnits.addAll(callGraph.codeUnitsWithoutOutsideCallers());
    annotatedCodeUnits(classes).forEach(codeUnits::add);
    return codeUnits;
  }

  private static Stream<JavaCodeUnit> annotatedCodeUnits(JavaClasses classes) {
    return classes.stream()
        .flatMap(javaClass -> javaClass.getCodeUnits().stream())
        .filter(FrameworkInvokedCodeUnits::isInvokedByFramework);
  }

  private static boolean isInvokedByFramework(JavaCodeUnit codeUnit) {
    return FRAMEWORK_INVOCATION_ANNOTATIONS.stream()
        .anyMatch(
            annotation ->
                codeUnit.isAnnotatedWith(annotation) || codeUnit.isMetaAnnotatedWith(annotation));
  }
}
