package works.momens.server.project.task;

import java.util.UUID;

/**
 * 조건부 draft 반영 요청.
 *
 * <p>반영할 값의 제목 길이는 이 record에서 검증합니다. {@code baseline}은 {@code convert} 시점에 기록한 스냅샷이므로 검증 대상에서
 * 제외합니다.
 *
 * @param baseline 반영을 허용하는 현재 값. {@code tasks}가 이 값 그대로일 때만 갱신합니다
 * @param draft 반영할 값
 */
public record ApplyTaskDraftCommand(UUID taskId, TaskDraftValues baseline, TaskDraftValues draft) {

  public ApplyTaskDraftCommand {
    TaskPolicy.requireTitleWithinLimit(draft.title());
  }
}
