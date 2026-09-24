package works.momens.server.signal.dev.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import works.momens.server.signal.SignalType;

/** dev 데모용 Signal 생성 요청(docs/design/signal-push-demo-design.md 5.2·5.3절). */
@Schema(description = "dev 데모용 Signal 생성 요청")
public record CreateDevSignalRequest(
    @Schema(description = "Signal type", example = "risk", implementation = SignalType.class)
        @NotBlank
        String type,
    @Schema(description = "Signal 제목", example = "결제 정책 결정 3일째 보류") @NotBlank String title,
    @Schema(description = "Signal 설명", example = "결제 정책 결정이 3일 동안 보류된 상태입니다.") @NotBlank
        String description,
    @Schema(description = "Signal 전체 영향") String impact,
    @Schema(description = "민수 제안") String minsuSuggestion,
    @Schema(description = "Signal 발생 시각(ISO-8601)") Instant occurredAt,
    @Schema(description = "근거 목록. 생략 또는 빈 배열 허용, 배열 순서가 sort_order") @Valid
        List<Evidence> evidence) {

  /** 요청에서 생략된 evidence를 빈 목록으로 정규화한다. */
  public List<Evidence> evidenceOrEmpty() {
    return evidence == null ? List.of() : evidence;
  }

  @Schema(description = "근거 한 건")
  public record Evidence(
      @Schema(description = "원천 종류", example = "slack")
          @NotBlank
          @Pattern(
              regexp = "slack|figma|github|file",
              message = "source_type은 slack, figma, github, file만 허용합니다.")
          String sourceType,
      @Schema(description = "원천 제목") String sourceTitle,
      @Schema(description = "원천 발췌") String sourceSnippet,
      @Schema(description = "원천 전체 내용") String sourceText,
      @Schema(description = "원천 URL") String sourceUrl,
      @Schema(description = "원천 발생 시각(ISO-8601)") Instant occurredAt,
      @Schema(description = "근거의 대상·변화·영향") @Valid Details details) {

    @Schema(description = "근거의 대상·변화·영향(각 공백 포함 30자 이하)")
    public record Details(
        @Schema(description = "대상") @Size(max = 30) String target,
        @Schema(description = "변화") @Size(max = 30) String change,
        @Schema(description = "영향") @Size(max = 30) String impact) {}
  }
}
