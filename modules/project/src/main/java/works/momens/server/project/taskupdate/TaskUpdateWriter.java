package works.momens.server.project.taskupdate;

import java.util.UUID;

public interface TaskUpdateWriter {

  TaskUpdateDetail create(CreateTaskUpdateCommand command);

  void delete(UUID taskId, UUID updateId, UUID userId);
}
