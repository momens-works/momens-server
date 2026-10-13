package works.momens.server.mobile.brief;

import java.util.List;
import java.util.UUID;
import works.momens.server.project.core.ProjectSnapshot;

/**
 * 브리프 화면에 필요한 정보를 모아 반환하는 조합 결과입니다. 프로젝트 스냅샷, 진행률, 시그널 요약(문단, 타입별 개수, 최신순 첫 페이지), 현재 우선순위를 담습니다.
 *
 * <p>{@code progress}는 태스크 상태를 기준으로 계산한 0~100 정수 퍼센트입니다(MOM-0800, ADR-0013). {@code
 * projects.progress}를 사용하지 않으므로 {@link ProjectSnapshot}에는 포함하지 않고 이 결과에서 함께 제공합니다.
 *
 * <p>{@code nextCursor}는 시그널 요약 다음 페이지를 여는 커서 문자열이고, 다음 페이지가 없으면 null입니다.
 *
 * <p>{@code signalDigest}는 그날 신호를 요약한 민수 산출물 문단입니다(ADR-0011). 아직 만들어지지 않았으면 null입니다. {@code items}와
 * 같은 하루 범위로 조회하므로 문단과 목록의 기준일이 항상 같습니다.
 */
public record MobileBrief(
    ProjectSnapshot project,
    int progress,
    String signalDigest,
    List<FilterCount> filters,
    List<SignalItem> items,
    String nextCursor,
    List<Priority> priorities) {

  /** 필터 칩 하나. key는 filter 쿼리 값(all 또는 signal type)이고, all은 노출 type 전체 합이라 화면 헤더 숫자로도 쓰입니다. */
  public record FilterCount(String key, String label, long count) {}

  public record SignalItem(UUID id, String type, String title) {}

  public record Priority(int rank, String title, UUID taskId) {}
}
