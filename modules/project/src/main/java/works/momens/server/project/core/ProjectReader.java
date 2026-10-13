package works.momens.server.project.core;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * project 모듈의 조회 public API.
 *
 * <p>모바일 API(MOM-60, MOM-61)와 다른 모듈이 project 내부 repository를 직접 참조하지 않고도 project 정보를 읽을 수 있도록 합니다.
 * 소프트 삭제된 project는 모든 메서드에서 없는 것으로 취급합니다. project가 없을 때 반환할 에러(PROJECT_NOT_FOUND 등)는 호출하는 쪽에서
 * 결정합니다.
 */
public interface ProjectReader {

  /** projectId가 속한 workspace id를 조회합니다. project 리소스 접근 권한 검사의 앞 단계에 사용합니다. */
  Optional<UUID> workspaceIdOf(UUID projectId);

  /**
   * 활성 프로젝트에 공유 잠금을 걸고 workspace id를 반환합니다. 호출자의 쓰기 트랜잭션이 끝날 때까지 프로젝트 UPDATE/DELETE를 막아, 하위 리소스 생성과
   * 프로젝트 삭제의 순서를 보장합니다. 트랜잭션이 필수입니다.
   */
  Optional<UUID> lockWorkspaceIdOf(UUID projectId);

  Optional<ProjectSnapshot> findSnapshot(UUID projectId);

  /**
   * workspaceIds에 속한 project를 모두 조회합니다. 정렬은 레거시와 같은 생성 시각 내림차순입니다.
   *
   * <p>사용자의 접근 범위(workspace 스코프)는 호출하는 쪽이 멤버십을 한 번 조회해서 확정해 넘깁니다. 이 모듈이 멤버십을 다시 읽지 않으므로, 호출하는 쪽의
   * 멤버십 스냅샷과 project 목록이 항상 같은 기준을 갖습니다(PR #42 리뷰의 role 누락 경합 대응).
   */
  List<ProjectSnapshot> listByWorkspaceIds(Collection<UUID> workspaceIds);
}
