package works.momens.server.web.source;

import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import works.momens.server.source.BeginInstallCommand;
import works.momens.server.source.SourceConnectionDetail;
import works.momens.server.source.SourceConnectionReader;
import works.momens.server.source.SourceInstaller;
import works.momens.server.web.WorkspaceAccessChecker;
import works.momens.server.workspace.membership.WorkspaceRole;

/**
 * source 연결 조회와 연결 시작 흐름을 조합하는 서비스입니다.
 *
 * <p>workspace와 source 모듈의 public API만 조합하며 도메인 정책은 소유하지 않습니다.
 *
 * <p>이 서비스에서는 권한만 확인합니다. 목록 조회에는 member 이상의 권한이 필요하고, 연결 시작에는 admin 이상의 권한이 필요합니다. 레거시와 동일한 기준입니다.
 * provider 지원 여부와 서버 설정 여부는 source 모듈에서 판정합니다.
 */
@Service
@RequiredArgsConstructor
class SourceConnectionService {

  private final WorkspaceAccessChecker workspaceAccessChecker;
  private final SourceConnectionReader sourceConnectionReader;
  private final SourceInstaller sourceInstaller;

  @Transactional(readOnly = true)
  public List<SourceConnectionDetail> list(UUID workspaceId, UUID userId) {
    workspaceAccessChecker.requireWorkspaceExists(workspaceId);
    workspaceAccessChecker.requireRoleAtLeast(workspaceId, userId, WorkspaceRole.MEMBER);
    return sourceConnectionReader.listDetailsByWorkspaceId(workspaceId);
  }

  public String beginInstall(UUID workspaceId, UUID userId, String provider) {
    workspaceAccessChecker.requireWorkspaceExists(workspaceId);
    workspaceAccessChecker.requireRoleAtLeast(workspaceId, userId, WorkspaceRole.ADMIN);
    return sourceInstaller.beginInstall(new BeginInstallCommand(workspaceId, userId, provider));
  }
}
