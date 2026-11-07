package works.momens.server.mobile.brief;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Base64;
import works.momens.server.common.api.FieldValidationException;

/**
 * 브리프 시그널 요약 페이지네이션의 mobile 전용 커서. signal 모듈의 커서를 기준일(KST) 앵커와 함께 감쌉니다.
 *
 * <p>브리프의 "오늘" 창은 요청 시각에 의존하는데, 페이지네이션 도중 자정을 넘기면 창이 다음 날로 밀려 이전 날의 남은 시그널이 사라집니다. 그래서 첫 페이지에서 정한
 * 기준일을 커서에 실어, 다음 페이지가 요청 시각과 무관하게 같은 창({@link BriefDay#rangeOf(LocalDate)})을 복원하도록 합니다.
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
