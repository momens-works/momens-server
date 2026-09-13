package works.momens.server.web.source;

import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import works.momens.server.common.api.BusinessException;
import works.momens.server.source.SourceErrorCode;
import works.momens.server.source.SourceRefDetail;
import works.momens.server.source.SourceRefReader;
import works.momens.server.source.SourceRefVerifier;
import works.momens.server.web.WorkspaceAccessChecker;
import works.momens.server.workspace.membership.WorkspaceRole;

/**
 * source-ref 검증 흐름을 조합하는 서비스입니다.
 *
 * <p>source-ref가 속한 워크스페이스를 먼저 조회한 뒤 요청자가 해당 워크스페이스의 멤버인지 확인합니다. source-ref가 없거나 소프트 삭제되었으면 워크스페이스를
 * 조회할 수 없으므로 권한 확인 전에 404를 반환합니다.
 */
@Service
@RequiredArgsConstructor
class SourceRefService {

  private final WorkspaceAccessChecker workspaceAccessChecker;
  private final SourceRefReader sourceRefReader;
  private final SourceRefVerifier sourceRefVerifier;

  public SourceRefDetail verify(UUID sourceRefId, UUID userId) {
    UUID workspaceId =
        sourceRefReader
            .findWorkspaceId(sourceRefId)
            .orElseThrow(
                () ->
                    new BusinessException(
                        SourceErrorCode.SOURCE_REF_NOT_FOUND,
                        Map.of("source_ref_id", sourceRefId.toString())));
    workspaceAccessChecker.requireRoleAtLeast(workspaceId, userId, WorkspaceRole.MEMBER);
    return sourceRefVerifier.verify(sourceRefId, userId);
  }
}
