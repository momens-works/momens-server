package works.momens.server.project.task;

import java.util.UUID;

/** 태스크 aggregate의 단일 쓰기 계약입니다. 인증과 입력 표면별 정규화는 호출하는 표면이 담당합니다. */
public interface TaskWriter {

  TaskSnapshot create(CreateTaskCommand command);

  TaskDetail update(UpdateTaskCommand command);

  TaskSnapshot patch(PatchTaskCommand command);

  TaskDetail toggleChecklistItem(UUID taskId, UUID itemId, boolean completed, Long version);

  void delete(UUID taskId, Long version);
}
