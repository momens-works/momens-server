package works.momens.server.workspace;

import java.util.UUID;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;

/**
 * workspaces와 users 행을 네이티브 SQL로 저장하는 테스트 시드 모음입니다.
 *
 * <p>workspace 모듈의 하위 도메인 테스트는 코어의 {@code WorkspaceRepository}가 package scope로 제한되어 있어 외래 키 제약을
 * 만족하는 행을 SQL로 저장합니다. 동일한 코드가 테스트마다 중복되지 않도록 testFixtures에서 공용으로 관리합니다. workspaces나 users를 참조하는
 * 테이블을 가진 다른 모듈(source, context)의 통합 테스트에서도 {@code testFixtures(project(':workspace'))}로 이 클래스를 가져와
 * 사용할 수 있습니다.
 */
public final class WorkspaceSeedSql {

  private WorkspaceSeedSql() {}

  /** FK를 만족할 workspace 행을 삽입합니다. id는 클라이언트에서 만들고 감사 필드는 DB default로 채웁니다. */
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

  /** workspace_members.user_id FK를 만족시킬 사용자 행을 삽입합니다. */
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
}
