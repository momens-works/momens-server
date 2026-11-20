package works.momens.server.signal;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/** 프로젝트 범위의 Signal 목록 조회 공개 API. 모든 메서드가 요청자의 workspace 멤버십을 검사합니다. */
public interface SignalListService {

  /**
   * 처리되지 않은 Signal을 cursor pagination으로 조회합니다.
   *
   * <p>태스크로 전환하거나 dismiss한 Signal과 soft delete된 Signal은 제외합니다. 생성 시각 내림차순으로 정렬하며, 생성 시각이 같으면 id
   * 내림차순으로 정렬합니다.
   *
   * @param cursor 이전 페이지의 {@link SignalSummaryPage#nextCursor()}. {@code null}이면 첫 페이지이며, 형식이 잘못된
   *     경우 {@code COMMON_VALIDATION_FAILED}로 실패합니다.
   * @param limit 페이지 크기. 1 이상이어야 하며, 상한을 초과하면 상한으로 제한해 조회합니다.
   */
  SignalSummaryPage listUnprocessed(UUID projectId, UUID userId, String cursor, int limit);

  /**
   * 생성 시각이 {@code [createdFrom, createdToExclusive)} 범위인 Signal을 커서 페이지로 조회합니다. 브리프의 당일 집계용이라 처리
   * 여부(태스크 전환이나 dismiss)와 무관하게 그 범위의 Signal을 담고, 소프트 삭제된 Signal은 제외합니다. 어떤 type을 보일지는 호출하는 표면이
   * 정합니다. 정렬은 생성 시각 내림차순이고 같으면 id 내림차순입니다.
   *
   * @param types 조회할 type 목록. null이거나 비면 type을 가리지 않고 전체를 조회합니다.
   * @param createdToExclusive 범위의 끝. 이 값은 포함하지 않습니다.
   * @param cursor 이전 페이지의 {@link SignalSummaryPage#nextCursor()}. null이면 첫 페이지. 형식이 잘못되면
   *     COMMON_VALIDATION_FAILED로 실패합니다.
   * @param limit 페이지 크기. 1 이상이어야 하고 상한을 넘으면 상한으로 줄여 조회합니다.
   */
  SignalSummaryPage listByCreatedRange(
      UUID projectId,
      UUID userId,
      Collection<String> types,
      Instant createdFrom,
      Instant createdToExclusive,
      String cursor,
      int limit);

  /**
   * 생성 시각이 {@code [createdFrom, createdToExclusive)} 범위인 Signal 개수를 type별로 집계합니다. 처리 여부와 무관하게 세고,
   * 소프트 삭제된 Signal은 제외합니다. 저장된 모든 type을 반환하고, 노출할 type은 호출하는 표면이 고릅니다.
   */
  Map<String, Long> countByCreatedRange(
      UUID projectId, UUID userId, Instant createdFrom, Instant createdToExclusive);
}
