package works.momens.server.project.taskupdate.internal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import works.momens.server.project.taskupdate.TaskUpdateDetail;
import works.momens.server.project.taskupdate.TaskUpdateReader;

@Service
@RequiredArgsConstructor
class TaskUpdateReaderImpl implements TaskUpdateReader {

  private final TaskUpdateRepository taskUpdateRepository;

  @Override
  @Transactional(readOnly = true)
  public List<TaskUpdateDetail> listByTaskId(UUID taskId) {
    return taskUpdateRepository
        .findByTaskIdAndDeletedAtIsNullOrderByCreatedAtAscIdAsc(taskId)
        .stream()
        .map(TaskUpdate::toDetail)
        .toList();
  }

  @Override
  @Transactional(readOnly = true)
  public List<TaskUpdateDetail> listPage(UUID taskId, Instant before, UUID beforeId, int limit) {
    return taskUpdateRepository
        .findPage(taskId, before, beforeId, PageRequest.of(0, limit))
        .stream()
        .map(TaskUpdate::toDetail)
        .toList();
  }

  @Override
  @Transactional(readOnly = true)
  public long countByTaskId(UUID taskId) {
    return taskUpdateRepository.countByTaskIdAndDeletedAtIsNull(taskId);
  }
}
