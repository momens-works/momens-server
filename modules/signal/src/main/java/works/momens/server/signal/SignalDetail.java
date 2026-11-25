package works.momens.server.signal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Signal 한 건의 상세 조회 결과.
 *
 * <p>상세 bottom sheet가 필요로 하는 조립 모델입니다. {@code impact}, {@code minsuSuggestion}은 worker/Minsu 미생산 시
 * null일 수 있습니다. 근거는 sort_order 순서로 담고, 각 근거의 {@code target}· {@code change}·{@code
 * impact}(대상·변화·영향)는 signal_evidence가 소유한 의미 값이며(ADR-0011), {@code source}·{@code
 * occurredAt}·{@code sourceUrl}은 source_refs에서 hydrate합니다. project·description·task draft는 상세 화면에서
 * 쓰지 않아 담지 않습니다(ADR-0011).
 */
public record SignalDetail(
    UUID id,
    String type,
    String title,
    String impact,
    String minsuSuggestion,
    List<Evidence> evidence) {

  public record Evidence(
      UUID sourceRefId,
      String source,
      Instant occurredAt,
      String target,
      String change,
      String impact,
      String sourceUrl) {}
}
