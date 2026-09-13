package works.momens.server.project.taskupdate.internal;

import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import works.momens.server.common.api.BusinessException;
import works.momens.server.common.api.CommonErrorCode;
import works.momens.server.common.api.FieldValidationException;
import works.momens.server.project.task.TaskErrorCode;
import works.momens.server.project.taskupdate.CreateTaskUpdateCommand;
import works.momens.server.project.taskupdate.TaskUpdateDetail;
import works.momens.server.project.taskupdate.TaskUpdateWriter;

@Service
@RequiredArgsConstructor
class TaskUpdateWriterImpl implements TaskUpdateWriter {

  private final TaskUpdateRepository taskUpdateRepository;

  @Override
  @Transactional
  public TaskUpdateDetail create(CreateTaskUpdateCommand command) {
    String body = command.body();
    if (body == null || body.trim().isEmpty()) {
      throw FieldValidationException.forField("body");
    }
    TaskUpdate update =
        TaskUpdate.create(
            command.workspaceId(),
            command.projectId(),
            command.taskId(),
            command.authorId(),
            body.trim(),
            normalizeKind(command.kind()),
            command.metadata());
    taskUpdateRepository.save(update);
    return update.toDetail();
  }

  @Override
  @Transactional
  public void delete(UUID taskId, UUID updateId, UUID userId) {
    TaskUpdate update =
        taskUpdateRepository
            .findByIdAndTaskIdAndDeletedAtIsNull(updateId, taskId)
            .orElseThrow(this::taskNotFound);
    if (!userId.equals(update.getAuthorId())) {
      throw new BusinessException(CommonErrorCode.AUTH_FORBIDDEN);
    }
    update.delete();
  }

  private BusinessException taskNotFound() {
    return new BusinessException(TaskErrorCode.TASK_NOT_FOUND);
  }

  private static String normalizeKind(String value) {
    String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    return switch (normalized) {
      case "", "comment" -> "comment";
      case "update" -> "update";
      default -> throw FieldValidationException.forField("kind");
    };
  }
}
