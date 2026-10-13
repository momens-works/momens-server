package works.momens.server.project.taskupdate;

import java.util.List;
import java.util.UUID;

public interface TaskUpdateReader {

  /** 소프트 삭제되지 않은 update를 생성 시각·id 오름차순으로 조회합니다. */
  List<TaskUpdateDetail> listByTaskId(UUID taskId);
}
