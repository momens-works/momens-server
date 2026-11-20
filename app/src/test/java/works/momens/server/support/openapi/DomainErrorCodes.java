package works.momens.server.support.openapi;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaFieldAccess;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import works.momens.server.common.api.CommonErrorCode;
import works.momens.server.common.api.ErrorCode;

/**
 * 도메인 에러 코드 enum을 판별하고 메서드가 참조하는 도메인 에러 코드를 찾습니다.
 *
 * <p>도메인 에러 코드 enum은 {@code ErrorCode}를 구현한 enum 중 {@code CommonErrorCode}를 제외한 타입입니다. enum 이름을 직접
 * 나열하지 않으므로 모듈에 새로운 에러 코드 enum이 추가되어도 이 클래스를 수정할 필요가 없습니다.
 *
 * <p>도메인 에러 코드는 enum 상수를 직접 참조해 {@code BusinessException}을 생성합니다. 따라서 메서드 본문에서 enum 상수를 참조하는 지점을 해당
 * 에러 코드가 발생할 수 있는 지점으로 판단합니다.
 */
final class DomainErrorCodes {

  private DomainErrorCodes() {}

  static boolean isDomainErrorCodeEnum(JavaClass javaClass) {
    return javaClass.isEnum()
        && javaClass.isAssignableTo(ErrorCode.class)
        && !javaClass.isEquivalentTo(CommonErrorCode.class);
  }

  /**
   * {@code @ApiException} 선언에서 해석한 에러 코드가 도메인 에러 코드인지 판단합니다.
   *
   * <p>{@code isDomainErrorCodeEnum}과 같은 기준을 적용해 {@code CommonErrorCode}가 아닌 enum 상수를 도메인 에러 코드로
   * 봅니다.
   */
  static boolean isDomainErrorCode(ErrorCode errorCode) {
    return errorCode instanceof Enum<?> && !(errorCode instanceof CommonErrorCode);
  }

  static Set<ErrorCode> referencedBy(JavaCodeUnit codeUnit) {
    return codeUnit.getFieldAccesses().stream()
        .filter(access -> isDomainErrorCodeEnum(access.getTargetOwner()))
        .filter(access -> !access.getOriginOwner().equals(access.getTargetOwner()))
        .map(DomainErrorCodes::constantOf)
        .flatMap(Optional::stream)
        .collect(Collectors.toSet());
  }

  private static Optional<ErrorCode> constantOf(JavaFieldAccess access) {
    String constantName = access.getTarget().getName();
    return Arrays.stream(access.getTargetOwner().reflect().getEnumConstants())
        .map(ErrorCode.class::cast)
        .filter(constant -> ((Enum<?>) constant).name().equals(constantName))
        .findFirst();
  }
}
