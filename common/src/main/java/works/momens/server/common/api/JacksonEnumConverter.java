package works.momens.server.common.api;

import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.core.convert.TypeDescriptor;
import org.springframework.core.convert.converter.ConditionalGenericConverter;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 문자열 요청 값을 enum으로 변환할 때 요청 body와 동일한 Jackson 규칙을 적용합니다.
 *
 * <p>Spring MVC의 기본 enum 변환은 enum 상수 이름을 기준으로 동작하므로, 요청 body에서 {@code @JsonValue} 값으로 받는 enum을 쿼리
 * 파라미터에서는 같은 방식으로 받을 수 없습니다. 이 converter는 enum 역직렬화에 대한 책임을 애플리케이션의 {@link ObjectMapper}에 위임합니다.
 * 따라서 {@code @JsonValue}를 사용하는 변환 규칙과 숫자 문자열을 enum으로 변환하지 않는 설정이 요청 body와 쿼리 파라미터에 동일하게 적용됩니다.
 * {@code Optional}, 배열, 컬렉션으로 선언한 쿼리 파라미터에는 Spring이 각 원소에 이 converter를 적용합니다.
 *
 * <p>이 converter에서 변환하지 못한 값은 Spring이 enum 상수 이름을 기준으로 다시 변환합니다. 따라서 쿼리 파라미터에서는 {@code @JsonValue}
 * 값과 enum 상수 이름을 모두 허용하고, 숫자 문자열과 허용되지 않은 값은 거부합니다.
 *
 * <p>빈 문자열은 Spring의 기본 변환과 동일하게 {@code null}로 반환합니다. 필수 쿼리 파라미터인 경우 누락된 값으로 처리됩니다.
 *
 * <p>{@code ConverterFactory} 대신 {@code ConditionalGenericConverter}로 구현합니다. Spring Boot는 {@code
 * ConverterFactory} 빈을 선언된 대상 타입({@code Enum})을 기준으로 적용하므로 개별 enum 타입의 변환에는 사용하지 않습니다.
 *
 * <p>app의 컴포넌트 스캔으로 자동 등록되며, app의 {@code @WebMvcTest}에도 자동으로 포함됩니다. 이 패키지를 스캔하지 않는 모듈의 컨트롤러 테스트에서는
 * {@code @Import}로 직접 등록합니다.
 */
@Component
@RequiredArgsConstructor
public class JacksonEnumConverter implements ConditionalGenericConverter {

  private final ObjectMapper objectMapper;

  @Override
  public Set<ConvertiblePair> getConvertibleTypes() {
    return Set.of(new ConvertiblePair(String.class, Enum.class));
  }

  @Override
  public boolean matches(TypeDescriptor sourceType, TypeDescriptor targetType) {
    return targetType.getObjectType().isEnum();
  }

  @Override
  public Object convert(Object source, TypeDescriptor sourceType, TypeDescriptor targetType) {
    String value = (String) source;
    if (value == null || value.isEmpty()) {
      return null;
    }
    return objectMapper.convertValue(value, targetType.getObjectType());
  }
}
