package works.momens.server.workspace.invitation.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 초대 토큰을 발급하고 해시합니다.
 *
 * <p>32바이트 난수를 URL에 사용할 수 있는 문자열로 인코딩해 토큰을 발급하고, DB에는 원문 대신 SHA-256 해시를 저장합니다. 초대 링크가 유출되더라도 DB에
 * 저장된 값만으로는 원래 토큰을 복원할 수 없도록 하기 위한 방식입니다.
 *
 * <p>해시 계산 방식은 레거시와 정확히 같아야 합니다. 이관 시점에 이미 이메일로 발송된 초대 링크에는 레거시가 발급한 토큰이 포함되어 있고, DB에는 해당 토큰의 해시만
 * 남아 있습니다. 해시 계산 방식이 조금이라도 다르면 기존에 발송된 초대 링크를 모두 사용할 수 없게 됩니다.
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
