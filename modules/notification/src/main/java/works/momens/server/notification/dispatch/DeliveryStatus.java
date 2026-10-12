package works.momens.server.notification.dispatch;

import lombok.RequiredArgsConstructor;

/** {@link PushDelivery} 상태 저장값. */
@RequiredArgsConstructor
enum DeliveryStatus {
  PENDING("pending"),
  SENT("sent"),
  FAILED("failed"),
  CANCELLED("cancelled");

  private final String value;

  String value() {
    return value;
  }
}
