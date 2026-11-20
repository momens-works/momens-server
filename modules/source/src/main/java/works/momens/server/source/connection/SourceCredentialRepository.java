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

  /** 워크스페이스와 연결 상태를 가리지 않습니다. OAuth 재연결과 같은 순서로 행을 갱신해 교착을 피하도록 연결 id 순으로 반환합니다. */
  @Query(
      "select cr from SourceCredential cr, SourceConnection c"
          + " where c.id = cr.connectionId and c.sourceType = 'FIGMA'"
          + " and c.externalWorkspaceId = :figmaUserId order by cr.connectionId")
  List<SourceCredential> findFigmaByUser(String figmaUserId);
}
