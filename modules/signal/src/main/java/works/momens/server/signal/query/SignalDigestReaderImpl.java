package works.momens.server.signal.query;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import works.momens.server.common.api.BusinessException;
import works.momens.server.common.api.CommonErrorCode;
import works.momens.server.project.core.ProjectErrorCode;
import works.momens.server.project.core.ProjectReader;
import works.momens.server.signal.SignalDigestReader;
import works.momens.server.workspace.membership.WorkspaceMembershipReader;

/**
 * 시그널 요약 문단 조회 서비스. 프로젝트가 속한 workspace를 해석하고 요청자의 멤버십을 검사한 뒤 문단을 반환합니다. 검사 방식은 {@code
 * SignalListServiceImpl}과 같습니다.
 */
@Service
@RequiredArgsConstructor
class SignalDigestReaderImpl implements SignalDigestReader {

  private final SignalDigestRepository signalDigestRepository;
  private final ProjectReader projectReader;
  private final WorkspaceMembershipReader workspaceMembershipReader;

  @Override
  @Transactional(readOnly = true)
  public Optional<String> findByCreatedRange(
      UUID projectId, UUID userId, Instant createdFrom, Instant createdToExclusive) {
    // 멤버십 검사에서 해석한 workspace로 조회도 스코프한다(교차 워크스페이스 방어, signals 조회와 같은 방식).
    UUID workspaceId = requireMember(projectId, userId);
    return signalDigestRepository
        .findByProjectIdAndCreatedRange(
            workspaceId, projectId, createdFrom, createdToExclusive, Limit.of(1))
        .stream()
        .findFirst()
        .map(SignalDigest::getSummary);
  }

  private UUID requireMember(UUID projectId, UUID userId) {
    UUID workspaceId =
        projectReader
            .workspaceIdOf(projectId)
            .orElseThrow(
                () ->
                    new BusinessException(
                        ProjectErrorCode.PROJECT_NOT_FOUND,
                        Map.of("project_id", projectId.toString())));
    if (workspaceMembershipReader.roleOf(workspaceId, userId).isEmpty()) {
      throw new BusinessException(
          CommonErrorCode.AUTH_FORBIDDEN, Map.of("project_id", projectId.toString()));
    }
    return workspaceId;
  }
}
