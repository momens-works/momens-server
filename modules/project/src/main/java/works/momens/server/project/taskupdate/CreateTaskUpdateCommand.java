package works.momens.server.project.taskupdate;

import java.util.Map;
import java.util.UUID;

/**
 * 태스크 업데이트 생성에 필요한 입력입니다.
 *
 * <p>{@code workspaceId}와 {@code projectId}에는 호출자가 권한 확인 단계에서 확정한 값을 전달합니다. 이 모듈은 소속을 다시 조회하지 않고
 * 전달받은 값을 저장합니다. {@code CreateTaskCommand}와 동일한 계약을 따릅니다.
 */
public record CreateTaskUpdateCommand(
    UUID taskId,
    UUID workspaceId,
    UUID projectId,
    UUID authorId,
    String body,
    String kind,
    Map<String, Object> metadata) {}
