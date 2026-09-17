package works.momens.server.support.openapi;

import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.TryCatchBlock;
import java.util.List;
import works.momens.server.common.api.BusinessException;

/**
 * 도메인 에러 코드에 도달하는 호출을 감싸면서 {@code BusinessException}이나 그 상위 타입을 잡는 {@code try} 블록의 등록 목록입니다.
 *
 * <p>이러한 {@code try} 블록은 {@code catch} 블록의 처리 방식에 따라 호출 경로 탐색 여부가 달라집니다. ArchUnit은 {@code catch}
 * 블록에서 예외를 다시 던지는지 확인하는 데 필요한 정보를 제공하지 않으므로, 처리 방식을 직접 확인한 뒤 해당 메서드와 함께 등록합니다.
 *
 * <ul>
 *   <li>{@code TRANSLATES}: 예외를 잡아 JSON 에러 응답이 아닌 다른 응답으로 변환합니다. 도메인 에러 코드가 클라이언트에 전달되지 않으므로 {@code
 *       try} 블록 내부의 호출은 탐색하지 않습니다.
 *   <li>{@code RETHROWS}: 예외를 잡은 뒤 다시 던집니다. 도메인 에러 코드가 클라이언트에 전달되므로 {@code try} 블록 내부의 호출도 탐색합니다.
 * </ul>
 *
 * <p>각 항목에는 처리 방식과 판단 근거를 함께 기록합니다. {@code ExceptionCatchingTryBlocksTest}가 등록 목록과 코드를 대조하므로 이 조건에
 * 해당하는 {@code try} 블록을 추가할 때는 처리 방식과 관계없이 목록에 등록해야 합니다.
 *
 * <p>처리 방식은 메서드 단위로 적용합니다. 등록된 메서드에 같은 조건의 {@code try} 블록을 추가하면 새 블록에도 해당 메서드에 등록된 처리 방식을 적용합니다.
 */
final class ExceptionCatchingTryBlocks {

  static final List<RegisteredTryBlock> REGISTERED =
      List.of(
          new RegisteredTryBlock(
              "works.momens.server.auth.internal.web.WebAuthSessionImpl.completeLogin(jakarta.servlet.http.HttpServletRequest, java.lang.String, java.lang.String)",
              Handling.TRANSLATES,
              "Google OAuth 로그인이 실패하면 예외를 잡아 JSON 에러 응답 대신 실패 주소로 리다이렉트합니다."));

  private ExceptionCatchingTryBlocks() {}

  static boolean isInTranslatingTryBlock(JavaAccess<?> access) {
    return access.getContainingTryBlocks().stream()
        .filter(ExceptionCatchingTryBlocks::catchesBusinessException)
        .anyMatch(
            tryBlock -> isRegisteredAs(tryBlock.getOwner().getFullName(), Handling.TRANSLATES));
  }

  static boolean catchesBusinessException(TryCatchBlock tryBlock) {
    return tryBlock.getCaughtThrowables().stream()
        .anyMatch(caught -> caught.reflect().isAssignableFrom(BusinessException.class));
  }

  private static boolean isRegisteredAs(String codeUnitFullName, Handling handling) {
    return REGISTERED.stream()
        .anyMatch(
            registered ->
                registered.codeUnitFullName().equals(codeUnitFullName)
                    && registered.handling() == handling);
  }

  record RegisteredTryBlock(String codeUnitFullName, Handling handling, String reason) {}

  enum Handling {
    TRANSLATES,
    RETHROWS
  }
}
