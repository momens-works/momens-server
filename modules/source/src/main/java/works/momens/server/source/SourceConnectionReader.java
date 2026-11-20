package works.momens.server.source;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** source 연결 조회 public API입니다. 요청자 권한은 호출하는 쪽에서 확인합니다. */
public interface SourceConnectionReader {

  Optional<UUID> findWorkspaceId(UUID connectionId);

  /** 연결이 속한 워크스페이스를 반환합니다. 연결이 없으면 404 오류가 발생합니다. */
  UUID getWorkspaceId(UUID connectionId);

  /**
   * 워크스페이스에 속한 source 연결을 생성 시각 내림차순으로 조회합니다.
   *
   * <p>정렬 기준은 레거시의 연결 목록 조회 쿼리와 같습니다.
   */
  List<SourceConnectionDetail> listDetailsByWorkspaceId(UUID workspaceId);
}
