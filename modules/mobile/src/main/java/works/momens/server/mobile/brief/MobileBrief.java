package works.momens.server.mobile.brief;

import java.util.List;
import java.util.UUID;
import works.momens.server.project.core.ProjectSnapshot;

/**
 * 브리프 화면에 필요한 정보를 모아 반환하는 조합 결과입니다.
 *
 * <p>{@code progress}는 태스크 상태로 계산하며 {@code projects.progress}를 사용하지 않으므로(ADR-0013), {@link
 * ProjectSnapshot}에는 포함하지 않고 이 결과에서 함께 제공합니다.
 */
public record MobileBrief(
    ProjectSnapshot project,
    int progress,
    String signalDigest,
    List<FilterCount> filters,
    List<SignalItem> items,
    String nextCursor,
    List<Priority> priorities) {

  /** 화면의 기본 페이지 크기이며 서버 보호 상한과는 별개입니다. */
  public static final int SIGNAL_SUMMARY_PAGE_SIZE = 20;

  public static final int PRIORITY_LIMIT = 4;

  public record FilterCount(String key, String label, long count) {}

  public record SignalItem(UUID id, String type, String title) {}

  public record Priority(int rank, String title, UUID taskId) {}
}
