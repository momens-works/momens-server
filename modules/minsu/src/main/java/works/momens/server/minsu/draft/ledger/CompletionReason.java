package works.momens.server.minsu.draft.ledger;

/**
 * 원장 종료 사유.
 *
 * <p>사유를 상태로 만들지 않은 이유는 {@link #USER_EDITED}가 성공도 실패도 아니기 때문이다. 모델은 정상 생성했지만 반영하지 않았으므로 성공이 아니고,
 * 재시도 상한에 도달한 실패도 아니다. 상태는 진행 여부만 표현하고 사유는 이 컬럼이 따로 갖는다.
 *
 * <p>API는 사유를 노출하지 않는다. 모든 종료는 사유와 무관하게 {@code ready}로 매핑된다. 앱에게 필요한 정보는 "이 title이 앞으로 더 바뀌는가" 하나이고
 * 그 점에서 모든 사유가 같으며, 재생성 API가 없으므로 실패를 알아도 앱이 취할 행동이 없다. 사유는 원장과 관측에만 남는다.
 *
 * <p>{@code disabled}는 여기에 없다. 그것은 종료가 아니다. 설정을 되돌리면 계속 처리할 수 있는 작업을 종료로 기록하면 되살릴 방법이 없다. provider가
 * 비활성이거나 설정이 무효인 동안 원장은 {@code pending}으로 남고, 운영자가 명시적으로 끝내려는 경우에 쓰는 값은 {@link
 * #OPERATIONALLY_CLOSED}다.
 *
 * <p>허용값의 출처는 마이그레이션의 CHECK이고 이 enum이 그것을 코드로 옮긴 것이다. 저장값이 소문자라
 * {@code @Enumerated(EnumType.STRING)}은 맞지 않으므로 {@code notification}의 {@code DeliveryStatus}와 같이
 * 문자열 값을 직접 갖는다.
 */
enum CompletionReason {
  /** 모델 draft를 반영했다. */
  GENERATED("generated"),
  /** 사용자가 먼저 편집해 결과를 폐기했다. */
  USER_EDITED("user_edited"),
  /** 대상 task가 삭제됐다. */
  TASK_GONE("task_gone"),
  /** 운영 판단으로 수동 종료했다. */
  OPERATIONALLY_CLOSED("operationally_closed"),
  /** 원장 나이 상한을 넘겼다. */
  DEADLINE_EXCEEDED("deadline_exceeded"),
  /** 입력이 부족해 재시도 없이 끝냈다. */
  INSUFFICIENT_CONTEXT("insufficient_context"),
  /** claim 이후 설정이 무효해졌다. */
  INVALID_CONFIG("invalid_config"),
  /** 재시도 상한에 도달했다. */
  RETRY_EXHAUSTED("retry_exhausted");

  private final String value;

  CompletionReason(String value) {
    this.value = value;
  }

  String value() {
    return value;
  }
}
