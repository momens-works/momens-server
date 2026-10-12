package works.momens.server.minsu;

/**
 * 생성 원장 적재 실패.
 *
 * <p>호출자의 재시도·경합 흡수 로직이 이 실패를 다른 제약 위반과 구분할 수 있도록 전용 타입으로 던진다. 원장 적재 실패는 트랜잭션 전체를 롤백시켜 다음 요청이 <b>신규
 * 처리</b>로 다시 오는 경우이고, 커밋은 됐는데 응답만 유실된 replay와는 장애 성격이 다르다. 둘을 같은 예외로 섞으면 분석이 어긋난다.
 */
public class TaskDraftEnrollmentException extends RuntimeException {

  public TaskDraftEnrollmentException(String message, Throwable cause) {
    super(message, cause);
  }
}
