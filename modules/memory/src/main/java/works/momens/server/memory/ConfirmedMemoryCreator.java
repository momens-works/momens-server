package works.momens.server.memory;

import java.util.UUID;

/**
 * 후보 검토를 거치지 않고 확정 메모리를 저장합니다.
 *
 * <p>호출자는 워크스페이스 권한을 확인해야 합니다. 워크스페이스 초기 데이터 생성은 레거시와 같이 검색 반영 이벤트를 발행하지 않습니다.
 */
public interface ConfirmedMemoryCreator {

  UUID create(CreateConfirmedMemoryCommand command);
}
