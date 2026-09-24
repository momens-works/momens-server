package works.momens.server.user;

/**
 * 사용자의 로그인 수단을 제공하는 identity provider입니다.
 *
 * <p>`user_identities.provider`의 CHECK 제약에서 허용하는 값을 표현합니다. 현재는 Google만 허용합니다.
 */
public enum UserIdentityProvider {
  GOOGLE("google");

  private final String value;

  UserIdentityProvider(String value) {
    this.value = value;
  }

  public String value() {
    return value;
  }
}
