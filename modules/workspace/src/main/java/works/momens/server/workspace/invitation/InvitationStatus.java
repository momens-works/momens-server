package works.momens.server.workspace.invitation;

import java.util.Optional;

/** 만료는 DB 상태로 저장하지 않고 만료 시각으로 계산합니다. */
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
