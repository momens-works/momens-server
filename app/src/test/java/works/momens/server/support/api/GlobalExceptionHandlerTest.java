package works.momens.server.support.api;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import works.momens.server.common.api.BusinessException;
import works.momens.server.common.api.ErrorCode;
import works.momens.server.common.api.FieldValidationException;

/** 전역 예외 핸들러가 예외를 Standard 에러 응답으로 매핑하는지 검증합니다. */
// advice 테스트 전용 TestController로 슬라이스를 한정(실제 컨트롤러 스캔 방지).
@WebMvcTest(controllers = GlobalExceptionHandlerTest.TestController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({GlobalExceptionHandler.class, GlobalExceptionHandlerTest.TestController.class})
class GlobalExceptionHandlerTest {

  @Autowired private MockMvc mockMvc;

  @Test
  @DisplayName("BusinessException은 ErrorCode의 status·code·message와 details로 렌더링된다")
  void rendersBusinessException() throws Exception {
    mockMvc
        .perform(get("/test/business"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("TEST_NOT_FOUND"))
        .andExpect(jsonPath("$.error.message").value("테스트 리소스를 찾을 수 없습니다."))
        .andExpect(jsonPath("$.error.details.resourceId").value("abc"));
  }

  @Test
  @DisplayName("details가 없으면 error.details 필드를 생략한다")
  void omitsDetailsWhenAbsent() throws Exception {
    mockMvc
        .perform(get("/test/business-default"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("TEST_NOT_FOUND"))
        .andExpect(jsonPath("$.error.details").doesNotExist());
  }

  @Test
  @DisplayName("Bean Validation 실패는 COMMON_VALIDATION_FAILED와 details.fields로 매핑된다")
  void rendersValidationFailure() throws Exception {
    mockMvc
        .perform(
            post("/test/validate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"display_name\":\"\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("COMMON_VALIDATION_FAILED"))
        .andExpect(jsonPath("$.error.details.fields[0].field").value("display_name"))
        .andExpect(jsonPath("$.error.details.fields[0].reason").exists());
  }

  @Test
  @DisplayName("Bean Validation 필드명은 설정된 Jackson snake_case 전략을 그대로 따른다")
  void rendersValidationFieldUsingConfiguredJacksonNamingStrategy() throws Exception {
    mockMvc
        .perform(
            post("/test/validate-acronym")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"user_idtoken\":\"\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.details.fields[0].field").value("user_idtoken"));
  }

  @Test
  @DisplayName("수동 필드 검증 실패도 COMMON_VALIDATION_FAILED와 details.fields로 매핑된다")
  void rendersProgrammaticFieldValidationFailure() throws Exception {
    mockMvc
        .perform(get("/test/field-validation"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("COMMON_VALIDATION_FAILED"))
        .andExpect(jsonPath("$.error.message").value("요청 값이 유효하지 않습니다."))
        .andExpect(jsonPath("$.error.details.field").doesNotExist())
        .andExpect(jsonPath("$.error.details.fields[0].field").value("owner_user_ids"))
        .andExpect(
            jsonPath("$.error.details.fields[0].reason")
                .value("must contain only workspace members"));
  }

  @Test
  @DisplayName("enum 필드에 허용하지 않는 값을 보내면 COMMON_VALIDATION_FAILED와 필드 이름을 응답합니다")
  void rendersValidationFailureForUnknownEnumValue() throws Exception {
    mockMvc
        .perform(
            post("/test/enum")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"test_level\":\"unknown\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("COMMON_VALIDATION_FAILED"))
        .andExpect(jsonPath("$.error.details.fields[0].field").value("test_level"));
  }

  @Test
  @DisplayName("enum 필드에 빈 문자열을 보내도 COMMON_VALIDATION_FAILED와 필드 이름을 응답합니다")
  void rendersValidationFailureForEmptyEnumValue() throws Exception {
    mockMvc
        .perform(
            post("/test/enum")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"test_level\":\"\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("COMMON_VALIDATION_FAILED"))
        .andExpect(jsonPath("$.error.details.fields[0].field").value("test_level"));
  }

  @Test
  @DisplayName("enum 필드에 숫자를 보내면 COMMON_VALIDATION_FAILED와 해당 필드 이름을 응답한다")
  void rendersValidationFailureForNumericEnumValue() throws Exception {
    mockMvc
        .perform(
            post("/test/enum")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"test_level\":0}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("COMMON_VALIDATION_FAILED"))
        .andExpect(jsonPath("$.error.details.fields[0].field").value("test_level"));
  }

  @Test
  @DisplayName("잘못된 JSON은 COMMON_BAD_REQUEST로 매핑된다")
  void rendersBadRequestForMalformedJson() throws Exception {
    mockMvc
        .perform(post("/test/validate").contentType(MediaType.APPLICATION_JSON).content("{"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("COMMON_BAD_REQUEST"));
  }

  @Test
  @DisplayName("파라미터 타입 불일치는 COMMON_BAD_REQUEST로 매핑된다")
  void rendersBadRequestForTypeMismatch() throws Exception {
    mockMvc
        .perform(get("/test/typed").param("value", "not-a-number"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("COMMON_BAD_REQUEST"));
  }

  @Test
  @DisplayName("허용되지 않은 HTTP 메서드는 405 COMMON_METHOD_NOT_ALLOWED로 매핑된다")
  void rendersMethodNotAllowed() throws Exception {
    mockMvc
        .perform(get("/test/validate"))
        .andExpect(status().isMethodNotAllowed())
        .andExpect(jsonPath("$.error.code").value("COMMON_METHOD_NOT_ALLOWED"));
  }

  @Test
  @DisplayName("지원하지 않는 Content-Type은 415 COMMON_UNSUPPORTED_MEDIA_TYPE으로 매핑된다")
  void rendersUnsupportedMediaType() throws Exception {
    mockMvc
        .perform(post("/test/validate").contentType(MediaType.TEXT_PLAIN).content("name=x"))
        .andExpect(status().isUnsupportedMediaType())
        .andExpect(jsonPath("$.error.code").value("COMMON_UNSUPPORTED_MEDIA_TYPE"));
  }

  @Test
  @DisplayName("미매핑 4xx는 status를 보존하면서 body code는 COMMON_BAD_REQUEST로 떨어진다")
  void mapsUnmapped4xxToBadRequestCode() throws Exception {
    mockMvc
        .perform(get("/test/error-status/422"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.error.code").value("COMMON_BAD_REQUEST"));
  }

  @Test
  @DisplayName("미매핑 5xx는 status를 보존하면서 body code는 COMMON_INTERNAL_SERVER_ERROR로 떨어진다")
  void mapsUnmapped5xxToInternalCode() throws Exception {
    mockMvc
        .perform(get("/test/error-status/503"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.error.code").value("COMMON_INTERNAL_SERVER_ERROR"));
  }

  @Test
  @DisplayName("예기치 못한 예외는 500으로 매핑되고 내부 메시지를 노출하지 않는다")
  void rendersInternalServerErrorWithoutLeakingMessage() throws Exception {
    mockMvc
        .perform(get("/test/boom"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.error.code").value("COMMON_INTERNAL_SERVER_ERROR"))
        .andExpect(jsonPath("$.error.message").value("서버 내부 오류가 발생했습니다."))
        .andExpect(content().string(not(containsString("boom secret"))));
  }

  @RestController
  static class TestController {

    @GetMapping("/test/business")
    void business() {
      throw new BusinessException(TestErrorCode.TEST_NOT_FOUND, Map.of("resourceId", "abc"));
    }

    @GetMapping("/test/business-default")
    void businessDefault() {
      throw new BusinessException(TestErrorCode.TEST_NOT_FOUND);
    }

    @PostMapping("/test/validate")
    void validate(@Valid @RequestBody TestRequest request) {}

    @PostMapping("/test/validate-acronym")
    void validateAcronym(@Valid @RequestBody AcronymRequest request) {}

    @PostMapping("/test/enum")
    void enumBody(@RequestBody EnumRequest request) {}

    @GetMapping("/test/field-validation")
    void fieldValidation() {
      throw FieldValidationException.forField(
          "owner_user_ids", "must contain only workspace members");
    }

    @GetMapping("/test/typed")
    void typed(@RequestParam int value) {}

    @GetMapping("/test/error-status/{status}")
    void errorStatus(@PathVariable int status) {
      throw new ErrorResponseException(HttpStatusCode.valueOf(status));
    }

    @GetMapping("/test/boom")
    void boom() {
      throw new IllegalStateException("boom secret should not leak");
    }

    record TestRequest(@NotBlank String displayName) {}

    record AcronymRequest(@NotBlank String userIDToken) {}

    record EnumRequest(TestLevel testLevel) {}

    enum TestLevel {
      LOW("low");

      private final String value;

      TestLevel(String value) {
        this.value = value;
      }

      @JsonValue
      String value() {
        return value;
      }
    }
  }

  @RequiredArgsConstructor
  enum TestErrorCode implements ErrorCode {
    TEST_NOT_FOUND(404, "테스트 리소스를 찾을 수 없습니다.");

    private final int status;
    private final String defaultMessage;

    @Override
    public String code() {
      return name();
    }

    @Override
    public int status() {
      return status;
    }

    @Override
    public String defaultMessage() {
      return defaultMessage;
    }
  }
}
