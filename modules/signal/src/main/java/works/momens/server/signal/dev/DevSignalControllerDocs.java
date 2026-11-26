package works.momens.server.signal.dev;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import works.momens.server.common.api.ApiException;
import works.momens.server.common.api.CommonErrorCode;
import works.momens.server.project.core.ProjectErrorCode;
import works.momens.server.signal.dev.dto.request.CreateDevSignalRequest;
import works.momens.server.signal.dev.dto.response.CreateDevSignalResponse;

@Tag(name = "DevSignal", description = "dev 데모용 Signal 생성 API. local/dev/test 프로필에서만 존재합니다.")
interface DevSignalControllerDocs {

  @Operation(
      operationId = "devCreateSignal",
      summary = "dev 데모용 Signal 생성",
      description =
          "완전한 Signal(evidence 원본 포함)을 생성하고 signal.created outbox 이벤트를 같은 트랜잭션으로 저장합니다. commit되면"
              + " push 발송이 켜진 환경에서 notification 모듈이 workspace 전체 구성원의 활성 Android 기기로 FCM push를"
              + " 비동기로 발송합니다. 응답은 commit 완료를 뜻하며 FCM 발송 성공을 기다리지 않습니다.")
  @ApiResponse(
      responseCode = "201",
      description = "생성됨. 생성된 Signal 식별자를 반환합니다.",
      content = @Content(schema = @Schema(implementation = CreateDevSignalResponse.class)))
  @ApiException(
      value = ProjectErrorCode.class,
      codes = {"PROJECT_NOT_FOUND"})
  @ApiException(CommonErrorCode.class)
  CreateDevSignalResponse createSignal(
      @Parameter(description = "project 식별자") UUID projectId,
      @RequestBody(
              content =
                  @Content(
                      examples =
                          @ExampleObject(
                              name = "쿠폰 적용 실패 문의 급증(여름 세일)",
                              description = "데모용 기본 예시. Try it out에서 그대로 실행하면 완전한 Signal이 생성됩니다.",
                              value =
                                  """
                                  {
                                    "type": "change",
                                    "title": "할인 쿠폰 적용 실패 문의가 오늘 27건 접수됐습니다",
                                    "description": "고객 문의 채널에서 할인 쿠폰이 적용되지 않는다는 문의가 오늘 27건 접수됐습니다. 이벤트 화면에 쿠폰 제외 조건이 충분히 안내되지 않아 고객이 결제 단계에서 적용 불가 사실을 확인하고 있습니다.",
                                    "impact": "프로모션 신뢰도와 주문 완료율이 떨어질 수 있습니다.",
                                    "minsu_suggestion": "제외 상품과 쿠폰 적용 실패 사유를 결제 전에 안내해보세요.",
                                    "occurred_at": "2026-07-17T01:05:00.000Z",
                                    "evidence": [
                                      {
                                        "source_type": "slack",
                                        "source_title": "고객 문의 채널",
                                        "source_snippet": "쿠폰이 적용되지 않는다는 문의가 오늘 27건 접수됐습니다.",
                                        "source_text": "오늘 고객 문의 채널에 할인 쿠폰이 적용되지 않는다는 문의가 27건 접수됐습니다. 결제 단계에서 쿠폰 적용에 실패한 뒤 적용 조건을 문의하거나 구매를 포기하는 사례가 함께 확인되고 있습니다.",
                                        "source_url": "https://momens.slack.com/archives/CS/p202607151005",
                                        "occurred_at": "2026-07-17T01:05:00.000Z",
                                        "details": {
                                          "target": "고객 문의 채널",
                                          "change": "쿠폰 실패 문의 27건 접수",
                                          "impact": "결제 포기와 반복 문의 증가"
                                        }
                                      },
                                      {
                                        "source_type": "figma",
                                        "source_title": "여름 세일 이벤트 배너",
                                        "source_snippet": "쿠폰 제외 브랜드와 최소 주문 금액 안내가 없습니다.",
                                        "source_text": "여름 세일 이벤트 배너와 쿠폰 안내 화면에 제외 브랜드 및 최소 주문 금액 조건이 표시되지 않았습니다. 고객은 상품을 장바구니에 담고 결제를 진행한 뒤에야 쿠폰을 사용할 수 없다는 사실을 알게 됩니다.",
                                        "source_url": "https://www.figma.com/file/demo-coupon-policy",
                                        "occurred_at": "2026-07-17T00:30:00.000Z",
                                        "details": {
                                          "target": "여름 세일 이벤트 배너",
                                          "change": "쿠폰 적용 조건 안내 누락",
                                          "impact": "결제 중 적용 불가 인지"
                                        }
                                      },
                                      {
                                        "source_type": "file",
                                        "source_title": "쿠폰 입력 구간 분석",
                                        "source_snippet": "쿠폰 적용 실패 후 결제 이탈률이 11.3% 증가했습니다.",
                                        "source_text": "쿠폰 입력 구간의 결제 퍼널을 분석한 결과, 쿠폰 적용에 실패한 고객의 결제 이탈률이 이전 구간보다 11.3% 증가했습니다. 현재 추세가 이어지면 프로모션 구매 전환율이 목표보다 낮아질 수 있습니다.",
                                        "source_url": "https://drive.google.com/file/d/demo-coupon-funnel",
                                        "occurred_at": "2026-07-17T00:00:00.000Z",
                                        "details": {
                                          "target": "쿠폰 입력 구간",
                                          "change": "결제 이탈률 11.3% 증가",
                                          "impact": "프로모션 전환 목표 미달"
                                        }
                                      }
                                    ]
                                  }
                                  """)))
          CreateDevSignalRequest request);
}
