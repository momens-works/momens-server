package works.momens.server.project.task;

import java.util.UUID;

/** 태스크 aggregate의 단일 쓰기 계약입니다. 인증과 입력 표면별 정규화는 호출하는 표면이 담당합니다. */
public interface TaskWriter {

  TaskSnapshot create(CreateTaskCommand command);

  TaskDetail update(UpdateTaskCommand command);

  TaskSnapshot patch(PatchTaskCommand command);

  TaskDetail toggleChecklistItem(UUID taskId, UUID itemId, boolean completed, Long version);

  void delete(UUID taskId, Long version);

  /**
   * 작성자 권한을 확인한 후 태스크를 소프트 삭제합니다. 요청한 사용자가 작성자가 아니면 {@code AUTH_FORBIDDEN}으로 거부합니다. 작성자 여부와 관계없이
   * 멤버라면 삭제할 수 있는 웹 경로에서는 {@link #delete}를 사용합니다.
   */
  void deleteAsCreator(UUID taskId, UUID userId, Long version);

  /**
   * 작성자 권한을 확인한 뒤 소프트 삭제된 태스크를 복구합니다. 요청한 사용자가 작성자가 아니면 {@code AUTH_FORBIDDEN}으로 거부합니다. 이미 복구되었거나
   * 삭제되지 않은 태스크는 변경하지 않고 이벤트도 발행하지 않으므로 동일한 복구 요청을 재시도해도 결과가 같습니다. 복구된 태스크는 삭제 직전의 상태, 담당자, role,
   * 완료 기준을 유지합니다.
   */
  void restoreAsCreator(UUID taskId, UUID userId);
}
