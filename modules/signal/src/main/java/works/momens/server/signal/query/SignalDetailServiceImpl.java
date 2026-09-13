package works.momens.server.signal.query;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import works.momens.server.common.api.BusinessException;
import works.momens.server.common.api.CommonErrorCode;
import works.momens.server.signal.SignalDetail;
import works.momens.server.signal.SignalDetailService;
import works.momens.server.signal.SignalErrorCode;
import works.momens.server.source.SourceRefReader;
import works.momens.server.source.SourceRefView;
import works.momens.server.workspace.membership.WorkspaceMembershipReader;

/**
 * Signal 상세 조회 서비스.
 *
 * <p>미처리 Signal을 로드해(처리됐거나 소프트 삭제됐으면 SIGNAL_NOT_FOUND(404)) workspace 멤버십으로 접근을 검사하고(멤버 아니면
 * AUTH_FORBIDDEN(403)) 근거를 조립합니다. 처리된 Signal을 다시 보는 inbox는 MVP 이후라 미처리만 대상으로 합니다
 * (docs/spec/mobile-api.md 시그널 상세 절). 근거는 signal_evidence의 sort_order 순으로 읽어
 * target·change·impact(대상· 변화·영향, ADR-0011)를 담고, source_ref_id로 source 모듈을 hydrate해(ADR-0008 read
 * 경계) source·occurred_at· source_url을 채웁니다. 원본이 없는 근거는 건너뜁니다.
 */
@Service
@RequiredArgsConstructor
class SignalDetailServiceImpl implements SignalDetailService {

  private final SignalRepository signalRepository;
  private final SignalEvidenceRepository signalEvidenceRepository;
  private final WorkspaceMembershipReader workspaceMembershipReader;
  private final SourceRefReader sourceRefReader;

  @Override
  @Transactional(readOnly = true)
  public SignalDetail getDetail(UUID signalId, UUID userId) {
    Signal signal =
        signalRepository
            .findUnprocessedById(signalId)
            .orElseThrow(
                () ->
                    new BusinessException(
                        SignalErrorCode.SIGNAL_NOT_FOUND,
                        Map.of("signal_id", signalId.toString())));
    if (workspaceMembershipReader.roleOf(signal.getWorkspaceId(), userId).isEmpty()) {
      throw new BusinessException(
          CommonErrorCode.AUTH_FORBIDDEN, Map.of("signal_id", signalId.toString()));
    }
    return new SignalDetail(
        signal.getId(),
        signal.getType(),
        signal.getTitle(),
        signal.getImpact(),
        signal.getMinsuSuggestion(),
        hydrateEvidence(signal.getWorkspaceId(), signal.getId()));
  }

  private List<SignalDetail.Evidence> hydrateEvidence(UUID workspaceId, UUID signalId) {
    List<SignalEvidence> links =
        signalEvidenceRepository.findBySignalIdOrderBySortOrderAscSourceRefIdAsc(signalId);
    Map<UUID, SourceRefView> refs =
        sourceRefReader
            .findByIds(workspaceId, links.stream().map(SignalEvidence::getSourceRefId).toList())
            .stream()
            .collect(Collectors.toMap(SourceRefView::id, Function.identity()));
    return links.stream()
        .filter(link -> refs.containsKey(link.getSourceRefId()))
        .map(link -> toEvidence(link, refs.get(link.getSourceRefId())))
        .toList();
  }

  private static SignalDetail.Evidence toEvidence(SignalEvidence link, SourceRefView ref) {
    return new SignalDetail.Evidence(
        ref.id(),
        ref.sourceType(),
        ref.sourceCreatedAt(),
        link.getTarget(),
        link.getChange(),
        link.getImpact(),
        ref.sourceUrl());
  }
}
