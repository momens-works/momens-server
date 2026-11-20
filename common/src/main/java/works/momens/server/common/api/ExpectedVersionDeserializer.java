package works.momens.server.common.api;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/** 버전 비교 전에 소수나 문자열이 정수로 강제 변환되는 것을 막습니다. */
public final class ExpectedVersionDeserializer extends ValueDeserializer<Long> {
  @Override
  public Long deserialize(JsonParser parser, DeserializationContext context) {
    if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT)) {
      return context.reportInputMismatch(Long.class, "version must be an integer");
    }
    return parser.getLongValue();
  }
}
