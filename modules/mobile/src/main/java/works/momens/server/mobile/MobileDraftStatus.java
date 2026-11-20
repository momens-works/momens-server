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

  private MobileDraftStatus() {}

  public static String key(DraftStatus status) {
    return status.name().toLowerCase(Locale.ROOT);
  }
}
