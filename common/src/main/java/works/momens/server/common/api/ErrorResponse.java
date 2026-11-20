package works.momens.server.common.api;

import com.fasterxml.jackson.annotation.JsonInclude;

/** 전역 예외 핸들러와 보안 필터가 같은 응답 형식을 사용하도록 공유합니다. {@code details}가 없으면 필드를 생략합니다. */
public record ErrorResponse(Body error) {

  public static ErrorResponse of(String code, String message, Object details) {
    return new ErrorResponse(new Body(code, message, details));
  }

  public record Body(
      String code, String message, @JsonInclude(JsonInclude.Include.NON_NULL) Object details) {}
}
