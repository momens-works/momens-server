package works.momens.server.project.task.internal;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import works.momens.server.project.task.TaskScope;

interface TaskRepository extends JpaRepository<Task, UUID> {

  /** 상세용 단건 조회. 소프트 삭제된 태스크는 제외합니다. */
  Optional<Task> findByIdAndDeletedAtIsNull(UUID taskId);

  /**
   * draft 반영용 단건 조회. 조회한 행을 잠가 조건 검사와 갱신 사이에 다른 트랜잭션의 수정이 끼어들지 않게 합니다.
   *
   * <p>소프트 삭제된 태스크를 제외하므로 결과가 비었다는 것이 곧 반영할 대상이 없다는 뜻입니다. prod는 레거시 {@code momens-api}와 DB를 공유해 삭제가
   * 레거시 쪽에서 일어날 수 있습니다.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select t from Task t where t.id = :taskId and t.deletedAt is null")
  Optional<Task> lockByIdAndDeletedAtIsNull(UUID taskId);

  /** 소속 확인용 projection 조회. 태스크 본문을 읽지 않고 workspace와 project만 가져옵니다. */
  @Query(
      """
      select new works.momens.server.project.task.TaskScope(t.workspaceId, t.projectId)
      from Task t
      where t.id = :taskId and t.deletedAt is null
      """)
  Optional<TaskScope> findScopeById(UUID taskId);

  /**
   * 보드용 조회. 주어진 상태의 소프트 삭제되지 않은 태스크를 생성 시각 내림차순으로, 같은 시각은 id 내림차순으로 정렬합니다. id 보조 정렬은 같은 마이크로초 생성 행의
   * 순서를 고정하기 위한 것입니다(pagination-tiebreaker 규칙).
   */
  List<Task> findByProjectIdAndStatusInAndDeletedAtIsNullOrderByCreatedAtDescIdDesc(
      UUID projectId, Collection<String> statuses);

  List<Task> findByProjectIdAndDeletedAtIsNullOrderByCreatedAtDesc(UUID projectId);

  List<Task> findByWorkspaceIdAndDeletedAtIsNullOrderByCreatedAtDesc(UUID workspaceId);

  /**
   * 진행률 계산용 조회. 주어진 상태의 소프트 삭제되지 않은 태스크 수를 상태별로 조회합니다.
   *
   * <p>보드 조회와 동일한 조건(projectId, status, 소프트 삭제 제외)을 사용합니다. 전체 태스크 수와 done 태스크 수를 같은 조회 결과를 기준으로 계산해
   * 목록과 진행률이 항상 같은 기준을 사용하도록 합니다. 진행률 계산에는 개수만 필요하므로 본문과 정렬은 조회하지 않습니다.
   */
  @Query(
      """
      select new works.momens.server.project.task.internal.StatusCount(t.status, count(t))
      from Task t
      where t.projectId = :projectId
        and t.status in :statuses
        and t.deletedAt is null
      group by t.status
      """)
  List<StatusCount> countByStatus(UUID projectId, Collection<String> statuses);
}
