package works.momens.server.workspace.invitation.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 초대 토큰은 32바이트 난수로 발급하며 DB에는 SHA-256 해시만 보관합니다.
 *
 * <p>기존 초대 링크를 계속 수락할 수 있도록 해시 방식은 레거시와 같아야 합니다.
 */
final class InvitationToken {

  private static final int RAW_TOKEN_BYTES = 32;
  private static final SecureRandom RANDOM = new SecureRandom();

  private InvitationToken() {}

  static String generate() {
    byte[] bytes = new byte[RAW_TOKEN_BYTES];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  static String hash(String rawToken) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(rawToken.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
