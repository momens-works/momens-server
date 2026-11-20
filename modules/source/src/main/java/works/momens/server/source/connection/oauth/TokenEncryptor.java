package works.momens.server.source.connection.oauth;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * worker·레거시와 공유하는 AES-256-GCM 저장 형식입니다. 12바이트 nonce 뒤에 암호문을 붙이며 AAD·형식 접두사는 없습니다.
 *
 * <p>키는 base64로 인코딩한 32바이트여야 합니다. 레거시 암호문 fixture는 {@code TokenEncryptorTest}에서 검증합니다.
 */
class TokenEncryptor {

  private static final String TRANSFORMATION = "AES/GCM/NoPadding";
  private static final String ALGORITHM = "AES";
  private static final int KEY_BYTES = 32;
  private static final int NONCE_BYTES = 12;
  private static final int TAG_BITS = 128;

  private final SecretKeySpec key;
  private final SecureRandom random = new SecureRandom();

  TokenEncryptor(String base64Key) {
    byte[] decoded;
    try {
      decoded = Base64.getDecoder().decode(base64Key);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("token key must be base64", e);
    }
    if (decoded.length != KEY_BYTES) {
      throw new IllegalArgumentException("token key must decode to 32 bytes");
    }
    this.key = new SecretKeySpec(decoded, ALGORITHM);
  }

  private TokenEncryptor() {
    this.key = null;
  }

  static TokenEncryptor unavailable() {
    return new TokenEncryptor();
  }

  /**
   * 평문이 비어 있으면 {@code null}을 반환합니다.
   *
   * <p>레거시와 동일한 동작이며, 반환값은 nullable 컬럼에 저장됩니다.
   */
  byte[] encrypt(String plaintext) {
    if (plaintext == null || plaintext.isEmpty()) {
      return null;
    }
    byte[] nonce = new byte[NONCE_BYTES];
    random.nextBytes(nonce);
    byte[] sealed = run(Cipher.ENCRYPT_MODE, nonce, plaintext.getBytes(StandardCharsets.UTF_8));
    byte[] out = new byte[nonce.length + sealed.length];
    System.arraycopy(nonce, 0, out, 0, nonce.length);
    System.arraycopy(sealed, 0, out, nonce.length, sealed.length);
    return out;
  }

  /**
   * 값이 비어 있으면 빈 문자열을 반환합니다.
   *
   * <p>저장된 값이 nonce 길이보다 짧으면 유효하지 않은 암호문으로 판정해 실패시킵니다.
   */
  String decrypt(byte[] payload) {
    if (payload == null || payload.length == 0) {
      return "";
    }
    if (payload.length <= NONCE_BYTES) {
      throw new IllegalArgumentException("encrypted payload is too short");
    }
    byte[] nonce = Arrays.copyOfRange(payload, 0, NONCE_BYTES);
    byte[] sealed = Arrays.copyOfRange(payload, NONCE_BYTES, payload.length);
    return new String(run(Cipher.DECRYPT_MODE, nonce, sealed), StandardCharsets.UTF_8);
  }

  private byte[] run(int mode, byte[] nonce, byte[] input) {
    if (key == null) {
      throw new IllegalStateException("source token key is not configured");
    }
    try {
      Cipher cipher = Cipher.getInstance(TRANSFORMATION);
      cipher.init(mode, key, new GCMParameterSpec(TAG_BITS, nonce));
      return cipher.doFinal(input);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("source token cipher failed", e);
    }
  }
}
