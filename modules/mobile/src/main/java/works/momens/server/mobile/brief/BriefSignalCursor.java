package works.momens.server.mobile.brief;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Base64;
import works.momens.server.common.api.FieldValidationException;

/**
 * 브리프 시그널 요약 페이지네이션의 mobile 전용 커서. signal 모듈의 커서를 기준일(KST) 앵커와 함께 감쌉니다.
 *
 * <p>브리프의 당일 조회 범위는 요청 시각에 따라 결정됩니다. 페이지네이션 도중 하루의 경계를 넘기면 조회 범위가 다음 날로 바뀌어 이전 일자의 남은 시그널을 조회할 수
 * 없습니다. 이를 방지하기 위해 첫 페이지에서 결정한 기준일을 커서에 포함하고, 이후 페이지에서는 요청 시각과 관계없이 동일한 시간 범위({@link
 * BriefDay#rangeOf(LocalDate)})를 복원합니다.
 *
 * <p>기준일 경계는 mobile이 소유하므로({@link BriefDay}) signal 커서에 day 의미를 새어 넣지 않고 이 바깥 커서가 앵커를 담습니다. 안쪽
 * signal 커서는 base64url이라 {@code |} 문자를 포함하지 않아 구분자로 안전합니다.
 */
record BriefSignalCursor(LocalDate anchor, String signalCursor) {

  static BriefSignalCursor decode(String cursor) {
    try {
      String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
      String[] parts = raw.split("\\|");
      if (parts.length != 2) {
        throw new IllegalArgumentException("커서 구성 요소가 2개가 아니다");
      }
      return new BriefSignalCursor(LocalDate.parse(parts[0]), parts[1]);
    } catch (RuntimeException e) {
      throw FieldValidationException.forField("cursor");
    }
  }

  static String encode(LocalDate anchor, String signalCursor) {
    String raw = anchor + "|" + signalCursor;
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
  }
}
