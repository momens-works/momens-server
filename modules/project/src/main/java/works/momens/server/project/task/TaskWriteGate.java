package works.momens.server.project.task;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import works.momens.server.common.api.BusinessException;

/** 인계 중 신규 태스크 쓰기와 draft claim을 함께 중단하는 배포 설정입니다. */
@Component
public class TaskWriteGate {
  private final boolean paused;

  public TaskWriteGate(@Value("${momens.project.task-writes-paused:false}") boolean paused) {
    this.paused = paused;
  }

  public boolean isPaused() {
    return paused;
  }

  public void requireOpen() {
    if (paused) {
      throw new BusinessException(TaskErrorCode.TASK_WRITES_PAUSED);
    }
  }
}
