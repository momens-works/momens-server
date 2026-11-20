package works.momens.server.source;

import java.util.UUID;

/** source 연결 명령 public API입니다. 호출자가 권한을 확인한 workspaceId를 전달합니다. */
public interface SourceConnectionWriter {

  /** 새 webhook 등록 후 설정을 활성화합니다. 외부 transaction 안에서는 호출할 수 없습니다. */
  SourceConnectionDetail configureFigma(
      UUID workspaceId, UUID connectionId, ConfigureFigmaCommand command);

  /** 연결 상태를 바꾸지 않고 재동기화 요청 시각을 기록합니다. 실제 수집은 worker가 수행합니다. */
  void requestResync(UUID connectionId, UUID workspaceId);

  /** 비활성화를 commit한 뒤 Figma webhook 삭제를 시도합니다. 외부 transaction 안에서는 호출할 수 없습니다. */
  void disable(UUID workspaceId, UUID connectionId);
}
