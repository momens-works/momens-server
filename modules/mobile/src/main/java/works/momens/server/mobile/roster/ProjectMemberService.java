package works.momens.server.mobile.roster;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import works.momens.server.common.api.BusinessException;
import works.momens.server.common.api.CommonErrorCode;
import works.momens.server.project.core.ProjectErrorCode;
import works.momens.server.project.core.ProjectReader;
import works.momens.server.user.UserProfile;
import works.momens.server.user.UserService;
import works.momens.server.workspace.membership.WorkspaceMembershipReader;

/**
 * 프로젝트 멤버 조회 조합 서비스. 도메인 모듈 public API 3개(project, workspace, user)만 조합하고 도메인 정책을 소유하지 않습니다.
 *
 * <p>멤버의 범위는 project가 속한 workspace의 멤버십이고(요구사항 명세 권한 요구사항), 검색과 정렬은 조합 규칙이라 이 서비스가 소유합니다. readOnly
 * 트랜잭션을 조합 경계에 두면 안쪽 리더들이 REQUIRED 전파로 합류해 한 트랜잭션에서 일관되게 읽습니다(bootstrap 전례).
 */
@Service
@RequiredArgsConstructor
class ProjectMemberService {

  private final ProjectReader projectReader;
  private final WorkspaceMembershipReader workspaceMembershipReader;
  private final UserService userService;

  @Transactional(readOnly = true)
  public List<ProjectMember> list(UUID projectId, UUID userId, String query) {
    UUID workspaceId =
        projectReader
            .workspaceIdOf(projectId)
            .orElseThrow(
                () ->
                    new BusinessException(
                        ProjectErrorCode.PROJECT_NOT_FOUND,
                        Map.of("project_id", projectId.toString())));
    // 멤버 목록은 한 번만 조회하고, 요청자의 접근 권한 확인과 응답 구성에 동일한 조회 결과를 사용합니다.
    // 요청자의 멤버십을 별도로 조회하면 READ COMMITTED에서는 각 SQL 문 실행 시 최신 커밋을 확인하므로,
    // 두 조회 사이에 멤버십이 회수된 사용자가 멤버 목록을 조회할 수 있습니다.
    List<UUID> memberUserIds = workspaceMembershipReader.listMemberUserIds(workspaceId);
    if (!memberUserIds.contains(userId)) {
      throw new BusinessException(
          CommonErrorCode.AUTH_FORBIDDEN, Map.of("project_id", projectId.toString()));
    }
    // 검색은 이름 부분 일치에 대소문자 무시, 정렬은 이름 오름차순(같으면 id 보조)이다. 명세에 없는 세부라
    // 2026-07-04 가결정으로 구현했고 규칙은 docs/spec/mobile-api.md 프로젝트 멤버 절에 적었다.
    String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
    return userService.getProfiles(memberUserIds).stream()
        .filter(
            profile -> needle.isEmpty() || profile.name().toLowerCase(Locale.ROOT).contains(needle))
        .sorted(Comparator.comparing(UserProfile::name).thenComparing(UserProfile::id))
        .map(profile -> new ProjectMember(profile.id(), profile.name(), profile.avatarUrl()))
        .toList();
  }
}
