package works.momens.server.common.api;

/** 생략한 버전은 이전 클라이언트의 요청으로 허용합니다. */
public final class ExpectedVersion {
  private ExpectedVersion() {}

  public static void check(Long expected, long actual) {
    if (expected == null) {
      return;
    }
    if (expected < 0) {
      throw FieldValidationException.forField("version");
    }
    if (expected != actual) {
      throw new BusinessException(CommonErrorCode.COMMON_CONCURRENT_MODIFICATION);
    }
  }
}
