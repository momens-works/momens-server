package works.momens.server.workspace.membership;

import lombok.RequiredArgsConstructor;
import works.momens.server.common.api.ErrorCode;

/** 멤버십의 존재 여부와 역할 판정 규칙에서 발생하는 에러 코드입니다. */
@RequiredArgsConstructor
public enum MembershipErrorCode implements ErrorCode {
  WORKSPACE_MEMBER_NOT_FOUND(404, "워크스페이스 멤버를 찾을 수 없습니다."),
  WORKSPACE_INVALID_ROLE(400, "부여할 수 없는 역할입니다."),
  WORKSPACE_OWNER_PROTECTED(409, "owner 멤버는 변경하거나 제거할 수 없습니다."),
  WORKSPACE_SELF_REMOVAL_NOT_ALLOWED(409, "자기 자신은 제거할 수 없습니다."),
  WORKSPACE_MEMBER_ALREADY_EXISTS(409, "이미 워크스페이스에 참여 중인 멤버입니다."),
  WORKSPACE_INVITEE_NOT_FOUND(403, "초대할 사용자를 찾을 수 없습니다."),
  WORKSPACE_MEMBER_ROLE_CONFLICT(403, "이미 다른 역할로 워크스페이스에 참여 중인 멤버입니다.");

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
