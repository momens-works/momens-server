package works.momens.server.support.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnitAccess;
import com.tngtech.archunit.core.domain.JavaFieldAccess;
import com.tngtech.archunit.core.domain.TryCatchBlock;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * 도메인 에러 코드에 도달하는 호출을 감싸면서 {@code BusinessException}이나 그 상위 타입을 잡는 {@code try} 블록이 모두 {@code
 * ExceptionCatchingTryBlocks}에 등록되어 있는지 검증합니다.
 *
 * <p>코드에서 찾은 {@code try} 블록이 속한 메서드 집합과 등록 목록이 일치해야 테스트가 통과합니다. 등록되지 않은 {@code try} 블록이 추가되거나, 등록된
 * 메서드에서 해당 {@code try} 블록이 사라지면 테스트가 실패합니다.
 *
 * <p>등록된 처리 방식이 올바른지는 이 테스트에서 검증하지 않습니다. {@code catch} 블록이 예외를 다른 응답으로 변환하는지, 다시 던지는지는 목록에 등록할 때 직접
 * 확인해야 합니다. 하지만 처리 방식은 메서드 단위로 적용되므로, 한 메서드에 이러한 {@code try} 블록이 둘 이상 있으면 테스트가 실패합니다.
 */
class ExceptionCatchingTryBlocksTest {

  private final JavaClasses classes = MainSourceClasses.imported();
  private final CallGraph callGraph = new CallGraph(classes);

  @Test
  void registeredTryBlocksMatchTryBlocksCatchingDomainErrorCodes() {
    Set<String> catchingDomainErrorCodes =
        catchingTryBlocks()
            .map(tryBlock -> tryBlock.getOwner().getFullName())
            .collect(Collectors.toCollection(TreeSet::new));
    Set<String> registered =
        ExceptionCatchingTryBlocks.REGISTERED.stream()
            .map(ExceptionCatchingTryBlocks.RegisteredTryBlock::codeUnitFullName)
            .collect(Collectors.toCollection(TreeSet::new));

    assertThat(catchingDomainErrorCodes)
        .as(
            "도메인 에러 코드에 도달하는 호출을 감싸면서 BusinessException이나 그 상위 타입을 잡는 try 블록이 ExceptionCatchingTryBlocks의 등록 목록과 일치하지 않습니다. 새로 추가한 try 블록이라면 catch 블록이 예외를 다른 응답으로 변환하는지(TRANSLATES), 다시 던지는지(RETHROWS) 확인한 뒤 판단 근거와 함께 등록하세요. try 블록을 삭제했다면 등록 목록에서도 해당 항목을 제거하세요.")
        .isEqualTo(registered);
  }

  @Test
  void methodsHaveAtMostOneTryBlockCatchingDomainErrorCodes() {
    Map<String, Long> countByCodeUnit =
        catchingTryBlocks()
            .collect(
                Collectors.groupingBy(
                    tryBlock -> tryBlock.getOwner().getFullName(), Collectors.counting()));

    assertThat(countByCodeUnit)
        .as(
            "한 메서드에 도메인 에러 코드에 도달하는 try 블록이 둘 이상 있습니다. 등록된 처리 방식은 메서드 단위로 적용되므로 각 블록의 처리 방식이 달라도 구분할 수 없습니다. 메서드를 분리해 메서드마다 해당 try 블록이 하나만 존재하도록 수정하세요.")
        .allSatisfy((codeUnitFullName, count) -> assertThat(count).isEqualTo(1));
  }

  private Stream<TryCatchBlock> catchingTryBlocks() {
    return classes.stream()
        .flatMap(javaClass -> javaClass.getCodeUnits().stream())
        .flatMap(codeUnit -> codeUnit.getTryCatchBlocks().stream())
        .filter(ExceptionCatchingTryBlocks::catchesBusinessException)
        .filter(this::reachesDomainErrorCode);
  }

  private boolean reachesDomainErrorCode(TryCatchBlock tryBlock) {
    return tryBlock.getAccessesContainedInTryBlock().stream()
        .anyMatch(this::reachesDomainErrorCode);
  }

  private boolean reachesDomainErrorCode(JavaAccess<?> access) {
    if (access instanceof JavaFieldAccess fieldAccess) {
      return DomainErrorCodes.isDomainErrorCodeEnum(fieldAccess.getTargetOwner());
    }
    if (access instanceof JavaCodeUnitAccess<?> codeUnitAccess) {
      return callGraph.reachableThrough(codeUnitAccess).stream()
          .anyMatch(codeUnit -> !DomainErrorCodes.referencedBy(codeUnit).isEmpty());
    }
    return false;
  }
}
