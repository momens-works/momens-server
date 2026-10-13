package works.momens.server.signal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** action 모듈이 Signal 원본을 읽을 때 쓰는 공개 API. 소프트 삭제된 Signal은 없는 것으로 취급한다. */
public interface SignalReader {

  Optional<Snapshot> findLive(UUID signalId);

  /**
   * task draft 생성 입력으로 쓰는 의미 있는 근거를 {@code sort_order ASC, source_ref_id ASC}로 최대 10건 반환한다. 근거가 없으면
   * 빈 목록을 반환하고 source ref를 hydrate하지 않는다. 상세 조회와 달리 source 원문·URL·작성자는 포함하지 않는다.
   */
  List<DraftEvidence> findDraftEvidence(UUID signalId);

  /**
   * action 처리에 필요한 Signal 스냅샷.
   *
   * <p>{@code type}·{@code description}·{@code impact}는 task draft 생성 입력이다. evidence는 convert 신규
   * 처리에서만 필요하므로 여기 포함하지 않고 {@link #findDraftEvidence(UUID)}로 분리한다.
   */
  record Snapshot(
      UUID id,
      UUID workspaceId,
      UUID projectId,
      String type,
      String title,
      String description,
      String impact) {}

  record DraftEvidence(String target, String change, String impact) {}
}
