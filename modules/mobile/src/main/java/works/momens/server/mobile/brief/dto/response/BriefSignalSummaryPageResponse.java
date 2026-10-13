package works.momens.server.mobile.brief.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import works.momens.server.mobile.brief.MobileBriefSignalPage;

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
