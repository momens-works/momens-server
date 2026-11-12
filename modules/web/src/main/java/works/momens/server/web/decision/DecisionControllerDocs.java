package works.momens.server.web.decision;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.security.Principal;
import java.util.UUID;
import works.momens.server.common.api.ApiException;
import works.momens.server.common.api.CommonErrorCode;
import works.momens.server.project.core.ProjectErrorCode;
import works.momens.server.web.decision.dto.request.CreateDecisionRequest;
import works.momens.server.web.decision.dto.response.WebDecisionResponse;

@Tag(name = "Web", description = "웹 진입 API")
interface DecisionControllerDocs {
  @Operation(
      operationId = "createDecision",
      summary = "Decision 생성",
      description =
          "프로젝트에 Decision을 생성합니다. workspace member 이상이 호출할 수 있습니다. "
              + "저장과 outbox 발행을 함께 완료하며 검색 반영은 비동기입니다. 응답은 Standard 규격입니다.")
  @ApiResponse(
      responseCode = "201",
      description = "Decision 생성 성공",
      content =
          @Content(
              schema = @Schema(implementation = WebDecisionResponse.class),
              examples =
                  @ExampleObject(
                      name = "created",
                      value =
                          """
                          {
                            "id": "d574d489-378f-456a-8df7-05ba3714d400",
                            "project_id": "e674d489-378f-456a-8df7-05ba3714d401",
                            "title": "PostgreSQL 사용",
                            "context": "트랜잭션 일관성이 필요합니다.",
                            "rationale": "현재 운영 환경과 호환됩니다.",
                            "reversibility": "reversible",
                            "decision_maker": "f774d489-378f-456a-8df7-05ba3714d402",
                            "created_at": "2026-10-04T00:00:00Z",
                            "updated_at": "2026-10-04T00:00:00Z"
                          }
                          """)))
  @ApiException(
      value = ProjectErrorCode.class,
      codes = {"PROJECT_NOT_FOUND"})
  @ApiException(CommonErrorCode.class)
  WebDecisionResponse createDecision(
      @Parameter(description = "프로젝트 식별자") UUID projectId,
      CreateDecisionRequest request,
      Principal principal);
}
