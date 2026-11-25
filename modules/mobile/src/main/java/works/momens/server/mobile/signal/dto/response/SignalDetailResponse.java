package works.momens.server.mobile.signal.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import works.momens.server.signal.SignalDetail;

@Schema(description = "시그널 상세 응답")
public record SignalDetailResponse(
    UUID id,
    String type,
    String title,
    @Schema(description = "Signal 전체가 프로젝트에 미칠 영향 요약. 미생산 시 null입니다.", nullable = true)
        String impact,
    List<EvidenceResponse> evidence,
    @Schema(description = "민수 제안. 미생산 시 null입니다.", nullable = true) String minsuSuggestion) {

  public record EvidenceResponse(
      UUID sourceRefId,
      @Schema(
              description = "원본 출처입니다(예: slack, github, figma, notion, file). 목록에 없는 값이 올 수 있습니다.",
              example = "figma")
          String source,
      @Schema(nullable = true) Instant occurredAt,
      Details details,
      @Schema(nullable = true) String sourceUrl) {}

  @Schema(name = "SignalEvidenceDetails", description = "근거 한 건에서 관찰한 대상·변화·영향")
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
