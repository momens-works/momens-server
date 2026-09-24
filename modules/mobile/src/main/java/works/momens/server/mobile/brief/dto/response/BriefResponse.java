package works.momens.server.mobile.brief.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;
import works.momens.server.mobile.brief.MobileBrief;
import works.momens.server.project.core.ProjectSnapshot;
import works.momens.server.signal.SignalType;

/**
 * {@code GET /api/mobile/projects/{projectId}/brief} 응답. 응답 형식은 docs/spec/mobile-api.md 브리프 절을
 * 따릅니다.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@Schema(description = "프로젝트 브리프 응답")
public record BriefResponse(
    @Schema(description = "프로젝트 스냅샷") ProjectResponse project,
    @Schema(description = "시그널 요약") SignalSummaryResponse signalSummary,
    @Schema(description = "현재 우선순위(상위 4개, 정렬 순번 포함)") List<PriorityResponse> priorities) {

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  @Schema(description = "프로젝트 스냅샷")
  public record ProjectResponse(
      @Schema(description = "project 식별자") UUID id,
      @Schema(description = "이름", example = "Q2 Activation Readiness") String name,
      @Schema(description = "목표일. 미설정이면 null로 포함됩니다.", nullable = true) LocalDate targetDate,
      @Schema(
              description =
                  "진행률(0~100의 정수 퍼센트). 취소한 태스크를 제외한 전체 태스크 중 완료한 태스크의 비율이며, 소수점 이하는 버립니다.",
              example = "64")
          int progress,
      @Schema(description = "핵심 목표 요약. 작성 전이면 null로 포함됩니다.", nullable = true) String summary) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  @Schema(description = "시그널 요약")
  public record SignalSummaryResponse(
      @Schema(description = "시그널 요약 문단. 민수가 아직 만들지 않았으면 null로 포함됩니다.", nullable = true)
          String summary,
      @Schema(description = "필터 칩 목록. all이 맨 앞이고 나머지는 라벨 글자수 오름차순과 알파벳순")
          List<FilterResponse> filters,
      @Schema(description = "당일 시그널 요약 최신순 첫 페이지") List<SignalItemResponse> items,
      @Schema(description = "다음 페이지 커서. 다음 페이지가 없으면 null로 포함됩니다.", nullable = true)
          String nextCursor) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  @Schema(description = "필터 칩")
  public record FilterResponse(
      @Schema(description = "필터 키", example = "all") String key,
      @Schema(description = "화면 라벨", example = "All") String label,
      @Schema(description = "해당 type의 당일 시그널 수. all은 노출 type 전체 합") long count) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  @Schema(description = "현재 우선순위 항목")
  public record PriorityResponse(
      @Schema(description = "정렬 순번(1부터). 화면의 01~04 표기와 같습니다.", example = "1") int rank,
      @Schema(description = "태스크 제목") String title,
      @Schema(description = "task 식별자. 태스크 상세로 이동할 때 사용합니다.") UUID taskId) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  @Schema(description = "시그널 요약 항목")
  public record SignalItemResponse(
      @Schema(description = "signal 식별자") UUID id,
      @Schema(description = "Signal type", example = "risk", implementation = SignalType.class)
          String type,
      @Schema(description = "제목") String title) {}

  public static BriefResponse from(MobileBrief brief) {
    return new BriefResponse(
        toProject(brief.project(), brief.progress()),
        new SignalSummaryResponse(
            brief.signalDigest(),
            toFilters(brief.filters()),
            toItems(brief.items()),
            brief.nextCursor()),
        toPriorities(brief.priorities()));
  }

  private static ProjectResponse toProject(ProjectSnapshot snapshot, int progress) {
    return new ProjectResponse(
        snapshot.id(), snapshot.name(), snapshot.targetDate(), progress, snapshot.summary());
  }

  private static List<FilterResponse> toFilters(List<MobileBrief.FilterCount> filters) {
    return filters.stream()
        .map(count -> new FilterResponse(count.key(), count.label(), count.count()))
        .toList();
  }

  private static List<PriorityResponse> toPriorities(List<MobileBrief.Priority> priorities) {
    return priorities.stream()
        .map(priority -> new PriorityResponse(priority.rank(), priority.title(), priority.taskId()))
        .toList();
  }

  // 하위 엔드포인트 응답(BriefSignalSummaryPageResponse)이 같은 항목 매핑을 쓰므로 package-private로 연다.
  static List<SignalItemResponse> toItems(List<MobileBrief.SignalItem> items) {
    return items.stream()
        .map(item -> new SignalItemResponse(item.id(), item.type(), item.title()))
        .toList();
  }
}
