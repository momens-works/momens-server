package works.momens.server.mobile.signal.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import works.momens.server.signal.SignalDetail;

/**
 * {@code GET /api/mobile/signals/{signalId}} 응답. 형식은 docs/spec/openapi.json에서 확인합니다.
 *
 * <p>상단 라벨은 {@code type}에서 앱이 파생하므로 처리 상태 필드는 없습니다. {@code impact}, {@code minsuSuggestion}은
 * worker/Minsu 미생산 시 null입니다. 근거는 {@code details.target}·{@code change}·{@code impact}(대상·변화·영향,
 * ADR-0011)를 담고 {@code source}·{@code occurredAt}·{@code sourceUrl}은 source_refs에서 옵니다.
 * project·description·task draft·가능한 action 목록은 상세 화면에서 쓰지 않아 응답하지 않습니다(ADR-0011).
 */
@Schema(description = "시그널 상세 응답")
public record SignalDetailResponse(
    UUID id,
    String type,
    String title,
    @Schema(nullable = true) String impact,
    List<EvidenceResponse> evidence,
    @Schema(nullable = true) String minsuSuggestion) {

  public record EvidenceResponse(
      UUID sourceRefId,
      String source,
      @Schema(nullable = true) Instant occurredAt,
      Details details,
      @Schema(nullable = true) String sourceUrl) {}

  @Schema(description = "근거의 대상·변화·영향. signal_evidence가 소유하며 각 값은 공백 포함 30자 이하입니다.")
  public record Details(
      @Schema(nullable = true, maxLength = 30) String target,
      @Schema(nullable = true, maxLength = 30) String change,
      @Schema(nullable = true, maxLength = 30) String impact) {}

  public static SignalDetailResponse from(SignalDetail detail) {
    return new SignalDetailResponse(
        detail.id(),
        detail.type(),
        detail.title(),
        detail.impact(),
        toEvidenceList(detail),
        detail.minsuSuggestion());
  }

  private static List<EvidenceResponse> toEvidenceList(SignalDetail detail) {
    return detail.evidence().stream().map(SignalDetailResponse::toEvidence).toList();
  }

  private static EvidenceResponse toEvidence(SignalDetail.Evidence evidence) {
    return new EvidenceResponse(
        evidence.sourceRefId(),
        evidence.source(),
        evidence.occurredAt(),
        new Details(evidence.target(), evidence.change(), evidence.impact()),
        evidence.sourceUrl());
  }
}
