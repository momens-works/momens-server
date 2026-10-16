package works.momens.server.project.task;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import works.momens.server.project.core.ProjectReader;

public interface TaskReader {

  /**
   * 주어진 상태에 해당하는 태스크를 조회합니다. 어떤 상태를 보일지는 호출하는 표면이 정합니다(모바일 보드는 저장 상태 5종을 모두 넘깁니다. MOM-75). 소프트 삭제된
   * 태스크는 제외하며, 정렬은 생성 시각 내림차순이고 같으면 id 내림차순입니다.
   */
  List<BoardTask> listTasksByStatus(UUID projectId, Collection<String> statuses);

  /**
   * 태스크 한 건의 상세를 조회합니다. 소프트 삭제된 태스크는 빈 값으로 취급합니다. 없을 때 어떤 에러로 응답할지는 호출하는 쪽이 정합니다({@link
   * ProjectReader}와 같은 계약 스타일).
   */
  Optional<TaskDetail> findDetail(UUID taskId);

  /**
   * 태스크가 속한 workspace와 project를 조회합니다. 다른 하위 도메인이 태스크 내부 저장소를 참조하지 않고 소속만 확인할 때 사용합니다. 소프트 삭제된 태스크는
   * 빈 값으로 취급합니다.
   */
  Optional<TaskScope> findScope(UUID taskId);

  /** 표면 응답으로 매핑할 태스크 저장값 한 건을 조회합니다. */
  Optional<TaskSnapshot> findSnapshot(UUID taskId);

  /** MCP 조회용으로 태스크 자체의 workspace를 제한합니다. 소프트 삭제된 태스크는 제외합니다. */
  Optional<TaskSnapshot> findSnapshotInWorkspace(UUID workspaceId, UUID taskId);

  /** MCP의 MOM-label 조회입니다. 호출자는 label을 대문자로 정규화합니다. */
  Optional<TaskSnapshot> findSnapshotByLabel(UUID workspaceId, String label);

  /** 프로젝트에 속한 태스크 저장값을 생성 시각 내림차순으로 조회합니다. */
  List<TaskSnapshot> listSnapshotsByProjectId(UUID projectId);

  /** 워크스페이스에 속한 태스크 저장값을 생성 시각 내림차순으로 조회합니다. */
  List<TaskSnapshot> listSnapshotsByWorkspaceId(UUID workspaceId);

  /** 생성 시각·id 내림차순으로 cursor 이후 최대 limit개를 조회합니다. */
  List<TaskSnapshot> listPage(
      UUID workspaceId,
      UUID projectId,
      UUID assigneeId,
      boolean unassignedOnly,
      String status,
      Instant before,
      UUID beforeId,
      int limit);
}
