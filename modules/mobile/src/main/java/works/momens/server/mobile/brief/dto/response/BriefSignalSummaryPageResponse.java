package works.momens.server.mobile.brief.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import works.momens.server.mobile.brief.MobileBriefSignalPage;

/**
 * {@code GET /api/mobile/projects/{projectId}/brief/signal-summary} 응답. 필터 전환과 더보기 페이지 이동이 쓰는 커서
 * 페이지 한 장입니다(docs/spec/mobile-api.md 브리프 절).
 */
@Schema(description = "브리프 시그널 요약 페이지 응답")
public record BriefSignalSummaryPageResponse(
    @Schema(description = "당일 시그널 요약 최신순 페이지") List<BriefResponse.SignalItemResponse> items,
    @Schema(description = "다음 페이지 커서. 다음 페이지가 없으면 null로 포함됩니다.", nullable = true)
        String nextCursor) {

  public static BriefSignalSummaryPageResponse from(MobileBriefSignalPage page) {
    return new BriefSignalSummaryPageResponse(
        BriefResponse.toItems(page.items()), page.nextCursor());
  }
}
