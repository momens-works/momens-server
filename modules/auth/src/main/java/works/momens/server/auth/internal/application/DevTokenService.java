package works.momens.server.auth.internal.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import works.momens.server.auth.internal.config.DevTokenProperties;
import works.momens.server.auth.internal.jwt.JwtTokenService;
import works.momens.server.common.api.BusinessException;
import works.momens.server.common.config.DevOnly;
import works.momens.server.user.UserProfile;
import works.momens.server.user.UserService;

/**
 * dev 전용 테스트 사용자 access token 발급 orchestration. {@link DevOnly} 프로필에서만 등록됩니다.
 *
 * <p>호출자 제한(공유 시크릿)과 발급 대상 제한(allowlist)을 먼저 확인한 뒤, 운영과 동일한 {@link
 * JwtTokenService#issueAccessToken}으로 access token만 발급합니다(refresh·DB write 없음). 별도 서명 로직은 만들지
 * 않습니다(ADR-0004).
 */
@DevOnly
@Service
@RequiredArgsConstructor
@Slf4j
public class DevTokenService {

  private final DevTokenProperties properties;
  private final UserService userService;
  private final JwtTokenService jwtTokenService;

  /**
   * 공유 시크릿을 검증하고 allowlist 테스트 사용자의 access token을 발급합니다. {@code requestedEmail}이 비면 allowlist 첫
   * 사용자를 씁니다.
   */
  public String issueForTestUser(String providedSecret, String requestedEmail) {
    verifySecret(providedSecret);
    String email = resolveEmail(requestedEmail);
    // 기존 사용자는 조회만 합니다.
    // findOrCreate는 기존 행의 이름과 프로필 이미지를 갱신하므로 사용자가 없을 때만 호출합니다.
    UserProfile user =
        userService
            .findByEmail(email)
            .orElseGet(() -> userService.findOrCreate(email, email, null));
    String accessToken = jwtTokenService.issueAccessToken(user.id());
    log.info("dev access token issued userId={}", user.id());
    return accessToken;
  }

  /** 헤더 시크릿을 설정 시크릿과 상수시간 비교합니다. 타이밍 공격을 피하려 {@link MessageDigest#isEqual}을 씁니다. */
  private void verifySecret(String providedSecret) {
    if (providedSecret == null
        || !MessageDigest.isEqual(
            providedSecret.getBytes(StandardCharsets.UTF_8),
            properties.secret().getBytes(StandardCharsets.UTF_8))) {
      throw new BusinessException(DevTokenErrorCode.AUTH_DEV_TOKEN_SECRET_INVALID);
    }
  }

  private String resolveEmail(String requestedEmail) {
    if (requestedEmail == null || requestedEmail.isBlank()) {
      return properties.allowedEmails().getFirst();
    }
    if (!properties.allowedEmails().contains(requestedEmail)) {
      throw new BusinessException(DevTokenErrorCode.AUTH_DEV_TOKEN_EMAIL_NOT_ALLOWED);
    }
    return requestedEmail;
  }
}
