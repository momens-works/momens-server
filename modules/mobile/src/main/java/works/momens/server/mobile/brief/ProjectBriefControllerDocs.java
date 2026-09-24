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

/**
 * {@code /api/mobile/projects/{projectId}/brief} OpenAPI 문서. Swagger 애너테이션을 컨트롤러 구현과
 * 분리합니다(docs/spec/openapi.md).
 *
 * <p>401/403은 보안 필터가 Standard shape로 응답하고, 없는 project는 PROJECT_NOT_FOUND(404), project는 있는데
 * workspace 멤버가 아니면 AUTH_FORBIDDEN(403)입니다.
 */
@Tag(name = "Mobile", description = "모바일 앱 진입 API")
interface ProjectBriefControllerDocs {

  @Operation(
      operationId = "mobileGetBrief",
      summary = "프로젝트 브리프 조회",
      description =
          "브리프 화면(오늘의 브리프)의 초기 로드에 필요한 정보를 조회합니다. 시그널 요약은 당일 시그널의 최신순 첫 페이지(기본 20개)와 타입별 개수를"
              + " 담습니다. 클라이언트는 첫 페이지에서 최신 3개만 노출하고, 더보기 시 나머지 항목을 펼쳐 보여줍니다. 이후 20개를 초과하면 next_cursor로 다음"
              + " 페이지를 조회해 무한 스크롤을 이어갑니다. 최신 3개만 노출하는 것은 화면 정책이며, 서버는 전체 첫 페이지를 반환합니다. change도 다른 type과"
              + " 똑같이 포함합니다. 필터 전환은 하위 엔드포인트 GET .../brief/signal-summary를 사용합니다.")
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
          "브리프 시그널 요약의 필터 전환과 후속 페이지 조회에 사용합니다. 커서 기반 페이지네이션을 사용하며, 정렬은 최신순(생성 시각 내림차순, 같으면 id 내림차순)입니다.\n\n"
              + "브리프 조회는 all 필터의 첫 페이지를 함께 반환하므로, 더보기를 처음 누를 때는 이 엔드포인트를 호출하지 않습니다. "
              + "필터를 변경하면 해당 타입의 첫 페이지를 조회하고, 브리프 조회에서 받은 첫 페이지를 모두 소비한 뒤에는 "
              + "직전 응답의 next_cursor를 cursor로 전달해 다음 페이지를 이어 조회합니다. "
              + "조회한 항목은 기존 목록 뒤에 추가하며, next_cursor가 null이면 더 조회할 데이터가 없습니다.")
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
      @Parameter(description = "필터 key. `all` 또는 Signal type을 사용합니다. 기본값은 `all`입니다.") String filter,
      @Parameter(
              description =
                  "직전 응답의 next_cursor입니다. 없으면 첫 페이지를 조회하며, next_cursor가 null이 될 때까지 이어 조회하면 모든 항목을 받을 수 있습니다.")
          String cursor,
      @Parameter(
              description =
                  "페이지 크기입니다. 없거나 0이면 기본값 20을 사용합니다. 최대 50까지 요청할 수 있으며, 이를 초과하면 50으로 제한합니다.")
          Integer limit,
      Principal principal);
}
