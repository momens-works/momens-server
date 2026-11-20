package works.momens.server.common.api;

/**
 * 클라이언트 분기용 안정 코드와 HTTP status를 묶습니다.
 *
 * <p>{@code common}이 spring-web에 의존하지 않도록 status는 {@code int}로 표현합니다.
 */
public interface ErrorCode {

  /** {@code DOMAIN_REASON} 형식의 {@code UPPER_SNAKE_CASE} 안정 코드. */
  String code();

  int status();

  /** 사람에게 보여줄 수 있는 기본 메시지. 클라이언트가 파싱하지 않습니다. */
  String defaultMessage();
}
