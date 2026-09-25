package works.momens.server.mobile;

import java.util.Locale;
import works.momens.server.minsu.DraftStatus;

/**
 * {@code draft_status}의 wire 표기를 만드는 단일 출처. convert 응답과 태스크 상세가 같은 값을 씁니다(설계 7.2절).
 *
 * <p>어휘는 minsu가 소유하고 여기서는 표기만 맞춥니다. {@link MobileTaskPriority}처럼 enum을 따로 두지 않는 이유는 매핑할 것이 없기
 * 때문입니다. 공개 값은 {@code generating}과 {@code ready} 둘뿐이고 모든 종료 사유는 {@code ready}로 합쳐집니다(7.1절).
 */
public final class MobileDraftStatus {

  private MobileDraftStatus() {}

  /** 응답으로 반환하는 draft 생성 상태 값입니다. */
  public static String key(DraftStatus status) {
    return status.name().toLowerCase(Locale.ROOT);
  }
}
