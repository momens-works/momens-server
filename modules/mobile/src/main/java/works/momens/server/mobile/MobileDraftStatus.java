package works.momens.server.mobile;

import java.util.Locale;
import works.momens.server.minsu.DraftStatus;

/**
 * {@code draft_status}의 wire 표기를 만드는 단일 출처. convert 응답과 태스크 상세가 같은 값을 씁니다.
 *
 * <p>상태 값 집합은 minsu에서 관리하며, 이 클래스는 모바일 응답 표기만 담당합니다. {@link MobileTaskPriority}와 동일한 원칙에 따라 허용 값
 * 집합은 각 도메인 모듈에서 관리하고, 모바일 모듈에서는 별도 enum을 선언하지 않습니다. 공개 값은 {@code generating}과 {@code ready}이며, 모든
 * 종료 사유는 {@code ready}로 통합합니다.
 */
public final class MobileDraftStatus {

  public static final String DESCRIPTION =
      "AI가 제목을 더 손볼 것이 남았는지. generating이면 나중에 다시 조회해야 합니다."
          + " ready면 정상 종료 기준으로 지금 title이 최종 값이며, 생성이 deadline을 넘겨 닫힌"
          + " 경우에만 뒤늦은 반영으로 한 번 더 바뀔 수 있습니다.";

  private MobileDraftStatus() {}

  public static String key(DraftStatus status) {
    return status.name().toLowerCase(Locale.ROOT);
  }
}
