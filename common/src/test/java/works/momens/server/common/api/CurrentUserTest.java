package works.momens.server.common.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.Principal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CurrentUserTest {

  @Test
  void returnsUserIdFromPrincipalName() {
    UUID userId = UUID.randomUUID();
    Principal principal = userId::toString;

    assertThat(CurrentUser.id(principal)).isEqualTo(userId);
  }

  @Test
  void throwsUnauthorizedWhenPrincipalNull() {
    assertThatThrownBy(() -> CurrentUser.id(null))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(CommonErrorCode.AUTH_UNAUTHORIZED);
  }

  @Test
  void throwsInvalidTokenWhenPrincipalNameNotUuid() {
    Principal principal = () -> "not-a-uuid";

    assertThatThrownBy(() -> CurrentUser.id(principal))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(CommonErrorCode.AUTH_INVALID_TOKEN);
  }

  @Test
  void throwsInvalidTokenWhenPrincipalNameNull() {
    Principal principal = () -> null;

    assertThatThrownBy(() -> CurrentUser.id(principal))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(CommonErrorCode.AUTH_INVALID_TOKEN);
  }
}
