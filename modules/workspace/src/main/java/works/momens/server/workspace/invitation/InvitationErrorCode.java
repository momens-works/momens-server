package works.momens.server.workspace.invitation;

import lombok.RequiredArgsConstructor;
import works.momens.server.common.api.ErrorCode;

@RequiredArgsConstructor
public enum InvitationErrorCode implements ErrorCode {
  INVITATION_INVALID_EMAIL(400, "초대할 이메일 형식이 올바르지 않습니다."),
  INVITATION_INVALID_TOKEN(400, "초대 토큰이 유효하지 않습니다."),
  INVITATION_EMAIL_MISMATCH(403, "초대받은 이메일과 로그인한 계정의 이메일이 일치하지 않습니다."),
  INVITATION_NOT_FOUND(404, "초대를 찾을 수 없습니다."),
  INVITATION_ALREADY_ACCEPTED(409, "이미 수락된 초대입니다."),
  INVITATION_EXPIRED(409, "만료된 초대입니다."),
  INVITATION_REVOKED(409, "폐기된 초대입니다."),
  INVITATION_EMAIL_SEND_FAILED(502, "초대 이메일을 발송하지 못했습니다.");

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
