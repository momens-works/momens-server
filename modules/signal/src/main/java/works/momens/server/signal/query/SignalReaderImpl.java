package works.momens.server.signal.query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import works.momens.server.signal.SignalReader;

/** action 모듈이 참조하는 최소 read 경계. Signal 존재·스코프와 draft 입력 값만 노출하고, 나머지는 이 nested 모듈 안에 둔다. */
@Service
@RequiredArgsConstructor
class SignalReaderImpl implements SignalReader {

  private static final int MAX_DRAFT_EVIDENCE_COUNT = 10;

  private final SignalRepository signalRepository;
  private final SignalEvidenceRepository signalEvidenceRepository;

  @Override
  @Transactional(readOnly = true)
  public Optional<Snapshot> findLive(UUID signalId) {
    return signalRepository
        .findByIdAndDeletedAtIsNull(signalId)
        .map(signal ->
            new Snapshot(
                signal.getId(),
                signal.getWorkspaceId(),
                signal.getProjectId(),
                signal.getType(),
                signal.getTitle(),
                signal.getDescription(),
                signal.getImpact()));
  }

  @Override
  @Transactional(readOnly = true)
  public List<DraftEvidence> findDraftEvidence(UUID signalId) {
    return signalEvidenceRepository
        .findDraftEvidence(signalId, PageRequest.ofSize(MAX_DRAFT_EVIDENCE_COUNT))
        .stream()
        .map(evidence ->
            new DraftEvidence(evidence.getTarget(), evidence.getChange(), evidence.getImpact()))
        .toList();
  }
}
