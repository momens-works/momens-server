package works.momens.server.project.milestone.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import works.momens.server.common.persistence.JpaAuditingConfig;
import works.momens.server.common.test.AbstractPostgresIntegrationTest;
import works.momens.server.project.ProjectSeedSql;
import works.momens.server.project.milestone.MilestoneDetail;
import works.momens.server.project.milestone.MilestoneReader;

/** 생성 경로와 독립적으로 기존 공유 DB 행의 소유자·정렬·삭제 필터를 검증하도록 SQL로 시드합니다. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, MilestoneReaderImpl.class})
@DisplayName("MilestoneReader 통합 테스트")
class MilestoneReaderIntegrationTest extends AbstractPostgresIntegrationTest {

  @Autowired private MilestoneReader milestoneReader;
  @Autowired private TestEntityManager entityManager;

  @Test
  @DisplayName("레거시가 저장한 필드를 그대로 반환한다")
  void listDetailsReturnsStoredFields() {
    Fixture fixture = new Fixture("stored");
    UUID milestoneId = fixture.insertMilestone("6월 릴리스");
    setStoredFields(
        milestoneId,
        "설명",
        LocalDate.of(2026, 6, 30),
        "active",
        "at_risk",
        40,
        "요약",
        Instant.parse("2026-06-01T09:00:00Z"));

    MilestoneDetail detail = fixture.onlyDetail();

    assertThat(detail.id()).isEqualTo(milestoneId);
    assertThat(detail.projectId()).isEqualTo(fixture.projectId);
    assertThat(detail.name()).isEqualTo("6월 릴리스");
    assertThat(detail.description()).isEqualTo("설명");
    assertThat(detail.targetDate()).isEqualTo(LocalDate.of(2026, 6, 30));
    assertThat(detail.status()).isEqualTo("active");
    assertThat(detail.healthStatus()).isEqualTo("at_risk");
    assertThat(detail.progress()).isEqualTo(40);
    assertThat(detail.summary()).isEqualTo("요약");
    assertThat(detail.lastContextAt()).isEqualTo(Instant.parse("2026-06-01T09:00:00Z"));
    assertThat(detail.createdAt()).isNotNull();
    assertThat(detail.updatedAt()).isNotNull();
  }

  @Test
  @DisplayName("선택 필드가 없으면 비우고, 나머지는 레거시 DEFAULT를 따른다")
  void listDetailsLeavesOptionalFieldsEmptyWhenNotStored() {
    Fixture fixture = new Fixture("sparse");
    fixture.insertMilestone("이름만 있는 마일스톤");

    MilestoneDetail detail = fixture.onlyDetail();

    assertThat(detail.description()).isNull();
    assertThat(detail.targetDate()).isNull();
    assertThat(detail.summary()).isNull();
    assertThat(detail.lastContextAt()).isNull();
    // 레거시 DEFAULT를 그대로 따른다. health_status의 기본값은 project의 'open'과 달리 'planned'다.
    assertThat(detail.status()).isEqualTo("planned");
    assertThat(detail.healthStatus()).isEqualTo("planned");
    assertThat(detail.progress()).isZero();
  }

  @Test
  @DisplayName("소프트 삭제된 마일스톤은 목록에서 제외한다")
  void softDeletedMilestoneIsGoneFromTheList() {
    Fixture fixture = new Fixture("gone");
    UUID milestoneId = fixture.insertMilestone("삭제됨");
    softDeleteMilestone(milestoneId);

    assertThat(fixture.details()).isEmpty();
  }

  @Test
  @DisplayName("owner_user_ids를 created_at, owner_user_id 순으로 반환한다")
  void ownerUserIdsFollowCreatedAtThenOwnerUserId() {
    Fixture fixture = new Fixture("owners");
    UUID milestoneId = fixture.insertMilestone("소유자 여럿");

    UUID late = ProjectSeedSql.insertUser(entityManager, "milestone-late@momens.works");
    // 같은 created_at 두 건은 owner_user_id로 tie-break 되므로, 큰 쪽과 작은 쪽을 함께 넣는다.
    UUID tieHigh = UUID.fromString("ffffffff-1111-0000-0000-000000000000");
    UUID tieLow = UUID.fromString("00000000-1111-0000-0000-000000000001");
    insertUserWithId(tieHigh, "milestone-tie-high@momens.works");
    insertUserWithId(tieLow, "milestone-tie-low@momens.works");

    insertMilestoneOwner(milestoneId, tieHigh, Instant.parse("2026-06-01T00:00:00Z"));
    insertMilestoneOwner(milestoneId, tieLow, Instant.parse("2026-06-01T00:00:00Z"));
    insertMilestoneOwner(milestoneId, late, Instant.parse("2026-06-02T00:00:00Z"));

    assertThat(fixture.onlyDetail().ownerUserIds()).containsExactly(tieLow, tieHigh, late);
  }

  @Test
  @DisplayName("소유자 행이 없으면 owner_user_ids를 빈 목록으로 둔다")
  void ownerUserIdsAreEmptyWhenNoRows() {
    Fixture fixture = new Fixture("no-owners");
    fixture.insertMilestone("소유자 행 없음");

    // project와 달리 폴백할 소유자 컬럼이 없어 빈 목록이 그대로 결과가 된다.
    assertThat(fixture.onlyDetail().ownerUserIds()).isEmpty();
  }

  @Test
  @DisplayName("넘긴 여러 project의 마일스톤을 생성 시각 내림차순으로 합치고 각자의 소유자를 붙인다")
  void listDetailsSpansGivenProjectsNewestFirstWithOwnOwners() {
    Fixture fixture = new Fixture("list");
    UUID otherProject =
        ProjectSeedSql.insertProject(entityManager, fixture.workspaceId, fixture.ownerId);
    UUID otherWorkspace = ProjectSeedSql.insertWorkspace(entityManager, "list-milestone-other");
    UUID outsideProject =
        ProjectSeedSql.insertProject(entityManager, otherWorkspace, fixture.ownerId);

    UUID older = fixture.insertMilestone("먼저", Instant.parse("2026-06-01T00:00:00Z"));
    UUID newer = insertMilestone(otherProject, "나중", Instant.parse("2026-06-02T00:00:00Z"));
    UUID outside =
        insertMilestone(outsideProject, "다른 워크스페이스", Instant.parse("2026-06-03T00:00:00Z"));

    UUID olderOwner = ProjectSeedSql.insertUser(entityManager, "list-older@momens.works");
    UUID newerOwner = ProjectSeedSql.insertUser(entityManager, "list-newer@momens.works");
    UUID outsideOwner = ProjectSeedSql.insertUser(entityManager, "list-outside@momens.works");
    insertMilestoneOwner(older, olderOwner, Instant.parse("2026-06-01T00:00:00Z"));
    insertMilestoneOwner(newer, newerOwner, Instant.parse("2026-06-01T00:00:00Z"));
    insertMilestoneOwner(outside, outsideOwner, Instant.parse("2026-06-01T00:00:00Z"));

    List<MilestoneDetail> details =
        milestoneReader.listDetailsByProjectIds(List.of(fixture.projectId, otherProject));

    // 넘긴 여러 project의 마일스톤이 한 목록으로 합쳐지고, 정렬은 project와 무관하게 생성 시각을 따른다. 넘기지 않은 project는 제외된다.
    assertThat(details).extracting(MilestoneDetail::id).containsExactly(newer, older);
    // 소유자를 배치로 한 번 읽어 붙이므로, 각 마일스톤이 자기 소유자만 갖는지가 그룹핑의 유일한 방어선이다.
    assertThat(details.get(0).ownerUserIds()).containsExactly(newerOwner);
    assertThat(details.get(1).ownerUserIds()).containsExactly(olderOwner);
  }

  @Test
  @DisplayName("project id 목록이 비면 빈 목록을 반환한다")
  void listsAreEmptyForNoProjects() {
    Fixture fixture = new Fixture("list-empty");
    UUID milestoneId = fixture.insertMilestone("범위 밖");

    assertThat(milestoneReader.listDetailsByProjectIds(List.of())).isEmpty();
    assertThat(milestoneReader.listPage(List.of(), null, null, 10)).isEmpty();
    assertThat(milestoneReader.listByIds(List.of(), List.of(milestoneId))).isEmpty();
  }

  @Test
  @DisplayName("페이지 조회는 넘긴 project에 한정하고 생성 시각·id 내림차순 cursor 이후를 반환한다")
  void listPageIsScopedToGivenProjectsAfterCursor() {
    Fixture fixture = new Fixture("page");
    UUID otherProject =
        ProjectSeedSql.insertProject(entityManager, fixture.workspaceId, fixture.ownerId);
    UUID first = fixture.insertMilestone("첫째", Instant.parse("2026-06-01T00:00:00Z"));
    UUID second = fixture.insertMilestone("둘째", Instant.parse("2026-06-02T00:00:00Z"));
    UUID third = fixture.insertMilestone("셋째", Instant.parse("2026-06-03T00:00:00Z"));
    insertMilestone(otherProject, "범위 밖", Instant.parse("2026-06-04T00:00:00Z"));

    List<MilestoneDetail> page =
        milestoneReader.listPage(List.of(fixture.projectId), null, null, 2);
    MilestoneDetail last = page.getLast();

    assertThat(page).extracting(MilestoneDetail::id).containsExactly(third, second);
    assertThat(milestoneReader.listPage(List.of(fixture.projectId), last.createdAt(), last.id(), 2))
        .extracting(MilestoneDetail::id)
        .containsExactly(first);
  }

  @Test
  @DisplayName("id 조회는 넘긴 project에 속한 살아 있는 마일스톤만 반환한다")
  void listByIdsIsScopedToGivenProjects() {
    Fixture fixture = new Fixture("by-ids");
    UUID otherProject =
        ProjectSeedSql.insertProject(entityManager, fixture.workspaceId, fixture.ownerId);
    UUID inside = fixture.insertMilestone("범위 안");
    UUID deleted = fixture.insertMilestone("삭제됨");
    softDeleteMilestone(deleted);
    UUID outside = insertMilestone(otherProject, "범위 밖", null);

    assertThat(
            milestoneReader.listByIds(
                List.of(fixture.projectId), List.of(inside, deleted, outside)))
        .extracting(MilestoneDetail::id)
        .containsExactly(inside);
  }

  private final class Fixture {

    private final UUID ownerId;
    private final UUID workspaceId;
    private final UUID projectId;

    private Fixture(String slug) {
      this.ownerId = ProjectSeedSql.insertUser(entityManager, slug + "-owner@momens.works");
      this.workspaceId = ProjectSeedSql.insertWorkspace(entityManager, slug + "-milestone");
      this.projectId = ProjectSeedSql.insertProject(entityManager, workspaceId, ownerId);
    }

    private UUID insertMilestone(String name) {
      return MilestoneReaderIntegrationTest.this.insertMilestone(projectId, name, null);
    }

    private UUID insertMilestone(String name, Instant createdAt) {
      return MilestoneReaderIntegrationTest.this.insertMilestone(projectId, name, createdAt);
    }

    private List<MilestoneDetail> details() {
      return milestoneReader.listDetailsByProjectIds(List.of(projectId));
    }

    private MilestoneDetail onlyDetail() {
      List<MilestoneDetail> details = details();
      assertThat(details).hasSize(1);
      return details.getFirst();
    }
  }

  /**
   * 마일스톤 행을 삽입합니다. {@code createdAt}이 있으면 지정합니다. 목록 정렬에 tie-break가 없어, 여러 건을 비교하는 테스트는 생성 시각을 명시해야
   * 결과가 결정적입니다.
   */
  private UUID insertMilestone(UUID projectId, String name, Instant createdAt) {
    UUID id = UUID.randomUUID();
    entityManager
        .getEntityManager()
        .createNativeQuery(
            "INSERT INTO milestones (id, project_id, name, created_at)"
                + " VALUES (?1, ?2, ?3, COALESCE(CAST(?4 AS timestamptz), NOW()))")
        .setParameter(1, id)
        .setParameter(2, projectId)
        .setParameter(3, name)
        .setParameter(4, createdAt)
        .executeUpdate();
    entityManager.clear();
    return id;
  }

  private void setStoredFields(
      UUID milestoneId,
      String description,
      LocalDate targetDate,
      String status,
      String healthStatus,
      int progress,
      String summary,
      Instant lastContextAt) {
    entityManager
        .getEntityManager()
        .createNativeQuery(
            "UPDATE milestones SET description = ?1, target_date = ?2, status = ?3,"
                + " health_status = ?4, progress = ?5, summary = ?6, last_context_at = ?7"
                + " WHERE id = ?8")
        .setParameter(1, description)
        .setParameter(2, targetDate)
        .setParameter(3, status)
        .setParameter(4, healthStatus)
        .setParameter(5, progress)
        .setParameter(6, summary)
        .setParameter(7, lastContextAt)
        .setParameter(8, milestoneId)
        .executeUpdate();
    entityManager.clear();
  }

  private void insertUserWithId(UUID id, String email) {
    entityManager
        .getEntityManager()
        .createNativeQuery("INSERT INTO users (id, email, name) VALUES (?1, ?2, ?3)")
        .setParameter(1, id)
        .setParameter(2, email)
        .setParameter(3, "이름")
        .executeUpdate();
  }

  private void insertMilestoneOwner(UUID milestoneId, UUID ownerUserId, Instant createdAt) {
    entityManager
        .getEntityManager()
        .createNativeQuery(
            "INSERT INTO milestone_owners (milestone_id, owner_user_id, created_at)"
                + " VALUES (?1, ?2, ?3)")
        .setParameter(1, milestoneId)
        .setParameter(2, ownerUserId)
        .setParameter(3, createdAt)
        .executeUpdate();
    entityManager.clear();
  }

  /** 조회할 삭제 상태를 네이티브 SQL로 구성합니다. */
  private void softDeleteMilestone(UUID milestoneId) {
    entityManager
        .getEntityManager()
        .createNativeQuery("UPDATE milestones SET deleted_at = NOW() WHERE id = ?1")
        .setParameter(1, milestoneId)
        .executeUpdate();
    entityManager.clear();
  }
}
