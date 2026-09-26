package works.momens.server.common.api;

import java.util.Map;

/**
 * cursor pagination 요청의 {@code cursor}와 {@code limit} query parameter를 해석한 결과입니다.
 *
 * <p>해석 규칙은 AIP-158을 따르며 화면과 관계없이 동일하므로 여러 모듈의 컨트롤러에서 함께 사용합니다. 화면별 기본 페이지 크기는 호출하는 쪽에서 전달하고, 페이지
 * 크기 상한은 목록을 조회하는 도메인 모듈에서 정합니다. {@code cursor}가 {@code null}이면 첫 페이지 요청을 의미합니다.
 */
public record CursorPageParams(String cursor, int pageSize) {

  /**
   * 요청의 query parameter를 해석합니다.
   *
   * <p>빈 cursor는 첫 페이지 요청으로 처리해 {@code null}로 두고, {@code limit}이 없거나 0이면 {@code defaultPageSize}를
   * 사용합니다.
   *
   * @throws BusinessException {@code limit}이 음수인 경우 ({@code COMMON_VALIDATION_FAILED})
   */
  public static CursorPageParams resolve(String cursor, Integer limit, int defaultPageSize) {
    String position = cursor == null || cursor.isBlank() ? null : cursor;
    if (limit == null || limit == 0) {
      return new CursorPageParams(position, defaultPageSize);
    }
    if (limit < 0) {
      throw new BusinessException(
          CommonErrorCode.COMMON_VALIDATION_FAILED, Map.of("limit", limit.toString()));
    }
    return new CursorPageParams(position, limit);
  }
}
