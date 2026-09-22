package works.momens.server.project.milestone.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface MilestoneRepository extends JpaRepository<Milestone, UUID> {

  Optional<Milestone> findByIdAndDeletedAtIsNull(UUID id);

  boolean existsByIdAndProjectIdAndDeletedAtIsNull(UUID id, UUID projectId);

  /**
   * 웹 목록 조회용 DTO projection입니다. 엔티티를 영속성 컨텍스트에 올리지 않고 필요한 컬럼만 읽습니다.
   *
   * <p>{@code milestones}에 {@code workspace_id}가 없어 {@code projects}를 조인합니다. 소속 project가 소프트 삭제되면 그
   * 마일스톤도 목록에서 빠집니다. 레거시가 조인 조건에 두 {@code deleted_at}을 모두 거는 동작과 같습니다(웹 snapshot 계약 4.4).
   *
   * <p>소유자는 여기서 조인하지 않습니다. 레거시는 행마다 상관 서브쿼리로 집계하지만, 계약이 배치 조회를 허용하고 행별 추가 조회를 금지합니다.
   *
   * <p>정렬은 레거시와 같은 생성 시각 내림차순입니다. 레거시에 tie-break가 없어 두지 않습니다.
   */
  @Query(
      """
      select new works.momens.server.project.milestone.internal.MilestoneDetailRow(
          m.id, m.projectId, m.name, m.description, m.targetDate, m.status,
          m.healthStatus, m.progress, m.summary, m.lastContextAt, m.createdAt, m.updatedAt)
      from Milestone m
      join Project p on p.id = m.projectId
      where p.workspaceId = :workspaceId
        and m.deletedAt is null
        and p.deletedAt is null
      order by m.createdAt desc
      """)
  List<MilestoneDetailRow> findDetailRowsByWorkspaceId(@Param("workspaceId") UUID workspaceId);
}
