package works.momens.server.source;

import lombok.RequiredArgsConstructor;
import works.momens.server.common.api.ErrorCode;

/** provider 미설정은 500, 외부 호출 실패는 502입니다. 레거시의 미설정 응답 501은 서버의 지원 status에 맞춰 500으로 변환합니다. */
@RequiredArgsConstructor
public enum SourceErrorCode implements ErrorCode {
  SOURCE_NOT_FIGMA_CONNECTION(400, "Figma 연결이 아닙니다."),
  SOURCE_FIGMA_INVALID_CONFIG(400, "Figma 팀과 하나 이상의 파일 키가 필요합니다."),
  SOURCE_FIGMA_WEBHOOK_UNCONFIGURED(500, "Figma webhook 설정이 서버에 없습니다."),
  SOURCE_FIGMA_REAUTH_REQUIRED(409, "Figma 연결을 다시 승인해야 합니다."),
  SOURCE_FIGMA_CREDENTIAL_INVALID(500, "저장된 Figma 자격 증명을 사용할 수 없습니다."),
  SOURCE_FIGMA_WEBHOOK_FAILED(502, "Figma webhook을 등록하지 못했습니다."),
  SOURCE_CONNECTION_CHANGED(409, "연결이 변경되었습니다. 최신 상태를 확인한 뒤 다시 시도해 주세요."),
  SOURCE_CONNECTION_ALREADY_DISABLED(400, "이미 비활성화된 소스 연결입니다."),
  SOURCE_UNSUPPORTED_PROVIDER(400, "지원하지 않는 provider입니다."),
  SOURCE_PROVIDER_UNCONFIGURED(500, "provider 설정이 서버에 없습니다."),
  SOURCE_OAUTH_INVALID_REQUEST(400, "승인 요청에 필요한 값이 없습니다."),
  SOURCE_OAUTH_INVALID_STATE(400, "승인 요청을 확인할 수 없습니다."),
  SOURCE_OAUTH_EXCHANGE_FAILED(502, "provider와 토큰을 주고받지 못했습니다."),
  SOURCE_CONNECTION_NOT_FOUND(404, "소스 연결을 찾을 수 없습니다."),
  SOURCE_REF_NOT_FOUND(404, "source-ref를 찾을 수 없습니다.");

  private final int status;
  private final String defaultMessage;

  @Override
  public String code() {
    return name();
  }

  @Override
  public int status() {
    return status;
  }

  @Override
  public String defaultMessage() {
    return defaultMessage;
  }
}
