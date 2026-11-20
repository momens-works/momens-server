package works.momens.server.project;

import java.time.Instant;
import java.util.UUID;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;

/**
 * project 모듈 테스트의 네이티브 SQL 시드 모음.
 *
 * <p>users/workspaces는 다른 모듈 소유 테이블이라 FK를 만족할 행을 SQL로 넣습니다. projects는 코어 {@code ProjectRepository}가
 * package scope 밖인 task 테스트를 위해 함께 둡니다. 민수 산출물(열린질문, 다음행동)은 이 서버에 쓰기 경로가 없어 생산자처럼 SQL로 넣습니다. 같은 헬퍼가
 * 테스트마다 복제되지 않도록 모듈 루트에 한 벌만 둡니다({@code workspace} 모듈의 {@code WorkspaceSeedSql}과 같은 방식).
 */
public final class ProjectSeedSql {

  private ProjectSeedSql() {}

  public static UUID insertUser(TestEntityManager entityManager, String email) {
    UUID id = UUID.randomUUID();
    entityManager
        .getEntityManager()
        .createNativeQuery("INSERT INTO users (id, email, name) VALUES (?1, ?2, ?3)")
        .setParameter(1, id)
        .setParameter(2, email)
        .setParameter(3, "이름")
        .executeUpdate();
    return id;
  }

  public static UUID insertWorkspace(TestEntityManager entityManager, String slug) {
    UUID id = UUID.randomUUID();
    entityManager
        .getEntityManager()
        .createNativeQuery("INSERT INTO workspaces (id, name, slug) VALUES (?1, ?2, ?3)")
        .setParameter(1, id)
        .setParameter(2, "모멘스")
        .setParameter(3, slug)
        .executeUpdate();
    return id;
  }

  public static UUID insertProject(
      TestEntityManager entityManager, UUID workspaceId, UUID ownerId) {
    UUID id = UUID.randomUUID();
    entityManager
        .getEntityManager()
        .createNativeQuery(
            "INSERT INTO projects (id, workspace_id, name, owner_id) VALUES (?1, ?2, ?3, ?4)")
        .setParameter(1, id)
        .setParameter(2, workspaceId)
        .setParameter(3, "프로젝트")
        .setParameter(4, ownerId)
        .executeUpdate();
    return id;
  }

  public static void insertProjectOwner(
      TestEntityManager entityManager, UUID projectId, UUID ownerUserId) {
    insertProjectOwner(entityManager, projectId, ownerUserId, Instant.now());
  }

  /** 생성 시각을 지정해 {@code project_owners} 행을 추가합니다. 소유자 목록의 정렬을 검증하는 테스트에서 이 값을 사용합니다. */
  public static void insertProjectOwner(
      TestEntityManager entityManager, UUID projectId, UUID ownerUserId, Instant createdAt) {
    entityManager
        .getEntityManager()
        .createNativeQuery(
            "INSERT INTO project_owners (project_id, owner_user_id, created_at)"
                + " VALUES (?1, ?2, ?3)")
        .setParameter(1, projectId)
        .setParameter(2, ownerUserId)
        .setParameter(3, createdAt)
        .executeUpdate();
    entityManager.clear();
  }

  /** 민수가 생산하는 열린질문 행을 삽입합니다. 서버에 쓰기 경로가 없어 SQL로 넣습니다. */
  public static void insertOpenQuestion(
      TestEntityManager entityManager, UUID id, UUID taskId, String body, int sortOrder) {
    entityManager
        .getEntityManager()
        .createNativeQuery(
            "INSERT INTO task_open_questions (id, task_id, body, sort_order)"
                + " VALUES (?1, ?2, ?3, ?4)")
        .setParameter(1, id)
        .setParameter(2, taskId)
        .setParameter(3, body)
        .setParameter(4, sortOrder)
        .executeUpdate();
    entityManager.clear();
  }

  /** 민수가 생산하는 다음행동을 채웁니다. 서버에 쓰기 경로가 없어 SQL로 넣습니다. */
  public static void setNextAction(
      TestEntityManager entityManager, UUID taskId, String nextAction) {
    entityManager
        .getEntityManager()
        .createNativeQuery("UPDATE tasks SET next_action = ?1 WHERE id = ?2")
        .setParameter(1, nextAction)
        .setParameter(2, taskId)
        .executeUpdate();
    entityManager.clear();
  }
}
