package works.momens.server.support.openapi;

import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.examples.Example;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import works.momens.server.common.api.ErrorCode;

public class SwaggerErrorExampleGenerator {

  private static final String APPLICATION_JSON = "application/json";

  public void addErrorResponses(Operation operation, List<? extends ErrorCode> errorCodes) {
    ApiResponses responses = operation.getResponses();
    if (responses == null) {
      responses = new ApiResponses();
      operation.setResponses(responses);
    }
    ApiResponses targetResponses = responses;

    Map<Integer, List<ErrorCode>> errorCodesByStatus =
        errorCodes.stream()
            .collect(
                Collectors.groupingBy(
                    ErrorCode::status, LinkedHashMap::new, Collectors.<ErrorCode>toList()));

    errorCodesByStatus.forEach(
        (status, codes) -> mergeIntoResponses(targetResponses, status, codes));
  }

  private void mergeIntoResponses(ApiResponses responses, int status, List<ErrorCode> errorCodes) {
    String statusKey = String.valueOf(status);
    ApiResponse apiResponse = responses.get(statusKey);
    if (apiResponse == null) {
      apiResponse = new ApiResponse().description(description(status));
      responses.addApiResponse(statusKey, apiResponse);
    }

    Content content = apiResponse.getContent();
    if (content == null) {
      content = new Content();
      apiResponse.setContent(content);
    }

    MediaType mediaType = content.get(APPLICATION_JSON);
    if (mediaType == null) {
      mediaType = new MediaType();
      content.addMediaType(APPLICATION_JSON, mediaType);
    }

    for (ErrorCode errorCode : errorCodes) {
      mediaType.addExamples(errorCode.code(), createExample(errorCode));
    }
  }

  private Example createExample(ErrorCode errorCode) {
    Map<String, Object> error = new LinkedHashMap<>();
    error.put("code", errorCode.code());
    error.put("message", errorCode.defaultMessage());
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("error", error);

    return new Example()
        .summary(errorCode.code())
        .description(errorCode.defaultMessage())
        .value(body);
  }

  private String description(int status) {
    HttpStatus httpStatus = HttpStatus.resolve(status);
    return httpStatus == null ? "Error" : httpStatus.getReasonPhrase();
  }
}
