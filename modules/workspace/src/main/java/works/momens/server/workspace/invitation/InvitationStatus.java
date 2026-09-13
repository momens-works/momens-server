package works.momens.server.workspace.invitation;

import java.util.Optional;

/**
 * 워크스페이스 초대 상태입니다.
 *
 * <p>DB에는 {@code pending}, {@code accepted}, {@code revoked} 세 가지 값만 저장합니다. {@code expired}는 저장하지
 * 않고 만료 시각이 지났는지를 기준으로 조회 시점에 계산합니다. 만료 상태를 반영하기 위한 별도 배치 작업을 두지 않는 레거시의 방식을 따릅니다.
 */
public enum InvitationStatus {
  PENDING("pending"),
  ACCEPTED("accepted"),
  REVOKED("revoked"),
  EXPIRED("expired");

  private final String value;

  InvitationStatus(String value) {
    this.value = value;
  }

  public static Optional<InvitationStatus> from(String value) {
    for (InvitationStatus status : values()) {
      if (status.value.equals(value)) {
        return Optional.of(status);
      }
    }
    return Optional.empty();
  }

  public String value() {
    return value;
  }
}
