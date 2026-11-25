package works.momens.server.mobile.brief;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.security.Principal;
import java.util.UUID;
import works.momens.server.common.api.ApiException;
import works.momens.server.common.api.CommonErrorCode;
import works.momens.server.mobile.brief.dto.response.BriefResponse;
import works.momens.server.mobile.brief.dto.response.BriefSignalSummaryPageResponse;
import works.momens.server.project.core.ProjectErrorCode;
import works.momens.server.signal.SignalListService;

@Tag(name = "Mobile", description = "모바일 앱 진입 API")
interface ProjectBriefControllerDocs {

  @Operation(
      operationId = "mobileGetBrief",
      summary = "프로젝트 브리프 조회",
      description =
          "브리프 화면의 초기 로드에 필요한 정보를 조회합니다. 시그널 요약은 당일 시그널의 최신순 첫 페이지(최대 "
              + ProjectBriefService.SIGNAL_SUMMARY_PAGE_SIZE
              + "개)와 타입별 개수를 담습니다. 다음 페이지와 필터 전환은 GET .../brief/signal-summary를 사용합니다. priorities는"
              + " todo와 in_progress 작업을 우선순위가 높은 순(같으면 생성 시각, id 오름차순)으로 최대 "
              + ProjectBriefService.PRIORITY_LIMIT
              + "개 담습니다.")
  @ApiResponse(
      responseCode = "200",
      description = "브리프 조회 성공",
      content = @Content(schema = @Schema(implementation = BriefResponse.class)))
  @ApiException(
      value = ProjectErrorCode.class,
      codes = {"PROJECT_NOT_FOUND"})
  @ApiException(CommonErrorCode.class)
  BriefResponse getBrief(
      @Parameter(description = "project 식별자") UUID projectId, Principal principal);

  @Operation(
      operationId = "mobileListBriefSignalSummaries",
      summary = "브리프 시그널 요약 페이지 조회",
      description =
          "브리프 시그널 요약의 필터 전환과 후속 페이지 조회에 사용합니다. 정렬은 생성 시각 내림차순이고, 같으면 id 내림차순입니다."
              + " 커서는 마지막으로 본 항목 기준이라 페이지 사이에 시그널이 처리되어도 다음 페이지 위치가 밀리지 않습니다.")
  @ApiResponse(
      responseCode = "200",
      description = "페이지 조회 성공",
      content = @Content(schema = @Schema(implementation = BriefSignalSummaryPageResponse.class)))
  @ApiException(
      value = ProjectErrorCode.class,
      codes = {"PROJECT_NOT_FOUND"})
  @ApiException(CommonErrorCode.class)
  BriefSignalSummaryPageResponse getSignalSummaryPage(
      @Parameter(description = "project 식별자") UUID projectId,
      @Parameter(
              description =
                  "필터 키. all 또는 signal type(change, decision, risk, question)입니다. 빈 값은 all과 같고, 알려지지 않은 type은"
                      + " 빈 목록을 반환합니다.")
          String filter,
      @Parameter(
              description =
                  "직전 응답의 next_cursor입니다. 없으면 첫 페이지를 조회하고, 형식이 잘못되면 COMMON_VALIDATION_FAILED입니다.")
          String cursor,
      @Parameter(
              description =
                  "페이지 크기입니다. 0이면 기본값을 사용하고, 상한("
                      + SignalListService.MAX_PAGE_SIZE
                      + ")을 넘으면 상한으로 줄입니다. 음수이면 COMMON_VALIDATION_FAILED입니다.",
              schema =
                  @Schema(
                      implementation = Integer.class,
                      defaultValue = "" + ProjectBriefService.SIGNAL_SUMMARY_PAGE_SIZE))
          Integer limit,
      Principal principal);
}
