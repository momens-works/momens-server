package works.momens.server.source.connection;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface SourceCredentialRepository extends JpaRepository<SourceCredential, UUID> {

  @Query(
      "select distinct c.externalWorkspaceId from SourceCredential cr, SourceConnection c"
          + " where c.id = cr.connectionId and c.sourceType = 'FIGMA'"
          + " and c.externalWorkspaceId is not null"
          + " and cr.expiresAt > :now and cr.expiresAt <= :until")
  List<String> findFigmaUsersExpiringBetween(Instant now, Instant until);

  /** 워크스페이스와 연결 상태를 가리지 않습니다. 가장 최근에 갱신된 행이 먼저 옵니다. */
  @Query(
      "select cr from SourceCredential cr, SourceConnection c"
          + " where c.id = cr.connectionId and c.sourceType = 'FIGMA'"
          + " and c.externalWorkspaceId = :figmaUserId order by cr.updatedAt desc")
  List<SourceCredential> findFigmaByUser(String figmaUserId);
}
