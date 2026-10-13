package works.momens.server.workspace.label;

import java.util.UUID;

/** 워크스페이스 단위 라벨을 발급합니다. worker 후보용 {@code SUG}는 이 서버에서 발급하지 않습니다. */
public interface LabelAllocator {

  /**
   * workspace 범위에서 다음 {@code MOM} 라벨을 발급합니다. 형식은 {@code MOM-0001}이고 workspace마다 1부터 독립적으로 증가합니다. 같은
   * workspace에 동시 발급이 들어와도 번호가 겹치지 않습니다.
   */
  String allocateMomLabel(UUID workspaceId);

  String allocateMemoryLabel(UUID workspaceId);

  /**
   * 워크스페이스 범위에서 다음 {@code PRJ} 라벨을 발급합니다. 라벨 형식은 {@code PRJ-0001}이며, 번호는 워크스페이스마다 1부터 독립적으로 증가합니다.
   */
  String allocateProjectLabel(UUID workspaceId);
}
