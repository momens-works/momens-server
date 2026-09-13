package works.momens.server.project.taskupdate;

import java.util.UUID;

/** 웹 Product API의 task update write public API입니다. */
public interface TaskUpdateWriter {

  TaskUpdateDetail create(CreateTaskUpdateCommand command);

  void delete(UUID taskId, UUID updateId, UUID userId);
}
