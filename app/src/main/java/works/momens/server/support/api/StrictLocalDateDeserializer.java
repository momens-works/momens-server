package works.momens.server.support.api;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoField;
import org.springframework.boot.jackson.JacksonComponent;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/**
 * 요청 body의 {@code LocalDate} 필드를 RFC 3339 full-date 형식({@code YYYY-MM-DD})의 문자열로만 역직렬화합니다.
 *
 * <p>Jackson의 기본 {@code LocalDate} 역직렬화는 정수를 epoch day로, 배열을 연월일로 해석하며, 날짜와 시각이 포함된 문자열에서는 날짜만
 * 추출합니다. leniency 설정을 비활성화해도 배열은 계속 날짜로 변환되므로, 이 클래스에서 역직렬화를 직접 처리합니다.
 *
 * <p>문자열이 아닌 값과 형식에 맞지 않는 문자열은 모두 거부합니다. 역직렬화에 실패하면 {@link GlobalExceptionHandler}에서 {@code
 * COMMON_VALIDATION_FAILED}로 응답합니다.
 *
 * <p>{@code null}은 Jackson이 이 클래스를 호출하지 않고 직접 처리하므로 기존과 동일하게 {@code null}로 변환됩니다.
 *
 * <p>{@code @JacksonComponent}로 등록되어 애플리케이션의 {@code JsonMapper}가 역직렬화하는 모든 {@code LocalDate} 필드에
 * 적용됩니다. MCP 도구 입력은 별도로 파싱하므로 영향을 받지 않습니다.
 */
@JacksonComponent
public class StrictLocalDateDeserializer extends ValueDeserializer<LocalDate> {

  /** RFC 3339 full-date 형식입니다. 연도를 네 자리로 제한해 {@code +57443-01-16}처럼 부호가 포함된 다섯 자리 이상의 연도를 거부합니다. */
  static final DateTimeFormatter FULL_DATE =
      new DateTimeFormatterBuilder()
          .appendValue(ChronoField.YEAR, 4)
          .appendLiteral('-')
          .appendValue(ChronoField.MONTH_OF_YEAR, 2)
          .appendLiteral('-')
          .appendValue(ChronoField.DAY_OF_MONTH, 2)
          .toFormatter()
          .withResolverStyle(ResolverStyle.STRICT);

  @Override
  public LocalDate deserialize(JsonParser parser, DeserializationContext context) {
    if (!parser.hasToken(JsonToken.VALUE_STRING)) {
      return (LocalDate) context.handleUnexpectedToken(LocalDate.class, parser);
    }
    String value = parser.getString();
    try {
      return LocalDate.parse(value, FULL_DATE);
    } catch (DateTimeParseException exception) {
      return (LocalDate)
          context.handleWeirdStringValue(LocalDate.class, value, "expected YYYY-MM-DD");
    }
  }
}
