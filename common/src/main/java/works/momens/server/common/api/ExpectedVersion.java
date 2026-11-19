package works.momens.server.common.api;

/**
 * 요청에 포함된 버전과 현재 버전을 비교해 수정 충돌을 검사합니다.
 *
 * <p>버전이 전달되면 클라이언트가 조회한 이후 발생한 변경도 충돌로 처리합니다. 버전이 생략되면 명시적인 버전 비교는 수행하지 않지만, 트랜잭션 실행 중 다른 요청이 동일한
 * 데이터를 변경하면 커밋 시점에 낙관적 락으로 충돌을 감지합니다.
 *
 * <p>버전 생략은 버전을 전달하지 않는 기존 클라이언트와의 호환성을 위해 허용합니다.
 */
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
