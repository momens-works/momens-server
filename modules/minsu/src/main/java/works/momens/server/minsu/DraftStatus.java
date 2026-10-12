package works.momens.server.minsu;

/**
 * task draft 생성 상태.
 *
 * <p>원장의 내부 상태·종료 사유와 달리 공개 값은 둘뿐이다. 모든 종료는 사유와 무관하게 {@link #READY}다. 사용자에게는 "AI가 더 손볼 것이 남았는가"만
 * 의미가 있고, 실패했는지 사용자가 먼저 고쳤는지는 앱이 알아야 할 정보가 아니다.
 */
public enum DraftStatus {
  /** 생성이 끝났거나 애초에 대상이 아니다. 지금 보이는 값이 최종값이다. */
  READY,
  /** 생성이 진행 중이다. 앱은 나중에 다시 조회해야 한다. */
  GENERATING
}
