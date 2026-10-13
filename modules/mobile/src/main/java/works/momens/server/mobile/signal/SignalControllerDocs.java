package works.momens.server.mobile.signal;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.security.Principal;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import works.momens.server.common.api.ApiException;
import works.momens.server.common.api.CommonErrorCode;
import works.momens.server.mobile.signal.dto.response.ConvertToTaskResponse;
import works.momens.server.mobile.signal.dto.response.DismissResponse;
import works.momens.server.mobile.signal.dto.response.SignalDetailResponse;
import works.momens.server.mobile.signal.dto.response.SignalListResponse;
import works.momens.server.project.core.ProjectErrorCode;
import works.momens.server.signal.SignalErrorCode;

@Tag(name = "Signal", description = "모바일 Signal API")
interface SignalControllerDocs {

  @Operation(
      operationId = "mobileListSignals",
      summary = "시그널 목록 조회",
      description =
          "프로젝트의 처리되지 않은 시그널을 생성 시각 내림차순으로 조회하며, 생성 시각이 같으면 id 내림차순으로 정렬합니다. "
              + "cursor pagination을 사용하며, 첫 요청은 cursor 없이 보내고 목록 끝에 도달하면 직전 응답의 `next_cursor`를 cursor로 전달해 다음 페이지를 조회합니다. "
              + "`next_cursor`가 `null`이면 더 조회할 데이터가 없습니다.")
  @ApiResponse(
      responseCode = "200",
      description = "미처리 시그널 목록의 한 페이지입니다. 조회 결과가 없으면 `signals`는 빈 배열입니다.",
      content = @Content(schema = @Schema(implementation = SignalListResponse.class)))
  @ApiException(
      value = ProjectErrorCode.class,
      codes = {"PROJECT_NOT_FOUND"})
  @ApiException(CommonErrorCode.class)
  SignalListResponse listSignals(
      @Parameter(description = "project 식별자") UUID projectId,
      @Parameter(description = "직전 응답의 `next_cursor`입니다. 없으면 첫 페이지를 조회합니다.") String cursor,
      @Parameter(
              description =
                  "페이지 크기입니다. 없거나 0이면 기본값 5를 사용합니다. 최대 50까지 요청할 수 있으며, 50을 초과하면 50으로 제한합니다.")
          Integer limit,
      Principal principal);

  @Operation(
      operationId = "mobileGetSignal",
      summary = "시그널 상세 조회",
      description =
          "시그널 상세 bottom sheet에 필요한 근거(대상·변화·영향)와 민수 제안을 조회합니다. 미처리 시그널만 대상이며, 처리·삭제된 시그널은"
              + " SIGNAL_NOT_FOUND입니다.")
  @ApiResponse(
      responseCode = "200",
      description = "시그널 상세.",
      content = @Content(schema = @Schema(implementation = SignalDetailResponse.class)))
  @ApiException(
      value = SignalErrorCode.class,
      codes = {"SIGNAL_NOT_FOUND"})
  @ApiException(CommonErrorCode.class)
  SignalDetailResponse getSignal(
      @Parameter(description = "signal 식별자") UUID signalId, Principal principal);

  @Operation(
      operationId = "mobileConvertSignalToTask",
      summary = "시그널을 작업으로 전환",
      description =
          "시그널을 원탭으로 작업에 등록합니다. 요청 body는 없습니다. 동기 경로에서는 민수가 생성한 draft(title, role, priority)로 작업을 만듭니다."
              + " 생성 비활성·설정 무효·입력 부족·생성 실패·출력 무효 시에는 fallback(title은 정규화된 시그널 제목으로 최대 15자, role은 pm, priority는 medium)을 사용합니다."
              + " 비동기 경로에서는 fallback으로 먼저 생성하고 draft_status가 generating인 동안 후속 생성을 진행합니다. 생성 결과는 title, role, priority가 초기 값과 같을 때만 반영하며, 생성 실패나 선행 편집 등으로 반영되지 않을 수 있습니다."
              + " draft_status가 ready가 되면 후속 생성이 종료된 상태이며 성공 여부를 뜻하지 않습니다. 최신 값은 작업 상세 조회로 확인합니다."
              + " 같은 시그널에 같은 action을 재요청하면 새 작업을 만들지 않고 기존 결과를 200으로"
              + " 반환합니다.")
  @ApiResponse(
      responseCode = "201",
      description = "새로 전환됨.",
      content = @Content(schema = @Schema(implementation = ConvertToTaskResponse.class)))
  @ApiResponse(
      responseCode = "200",
      description = "같은 action 재요청(멱등 replay).",
      content = @Content(schema = @Schema(implementation = ConvertToTaskResponse.class)))
  @ApiException(
      value = SignalErrorCode.class,
      codes = {"SIGNAL_NOT_FOUND", "SIGNAL_INVALID_STATE"})
  @ApiException(CommonErrorCode.class)
  ResponseEntity<ConvertToTaskResponse> convertToTask(
      @Parameter(description = "signal 식별자") UUID signalId, Principal principal);

  @Operation(
      operationId = "mobileDismissSignal",
      summary = "시그널을 처리하지 않고 닫음(dismiss)",
      description =
          "시그널을 작업으로 전환하지 않고 처리 완료로 기록합니다. 물리 삭제가 아니며, 같은 시그널에 dismiss를 재요청하면 기존 결과를 200으로"
              + " 반환합니다.")
  @ApiResponse(
      responseCode = "200",
      description = "처리 완료(신규 또는 멱등 replay).",
      content = @Content(schema = @Schema(implementation = DismissResponse.class)))
  @ApiException(
      value = SignalErrorCode.class,
      codes = {"SIGNAL_NOT_FOUND", "SIGNAL_INVALID_STATE"})
  @ApiException(CommonErrorCode.class)
  DismissResponse dismiss(
      @Parameter(description = "signal 식별자") UUID signalId, Principal principal);
}
