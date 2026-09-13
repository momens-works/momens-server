package works.momens.server.workspace.core;

import java.util.UUID;

/**
 * 워크스페이스 수정 요청.
 *
 * <p>세 필드는 모두 선택 사항입니다. 값이 없으면 기존 저장 값을 유지합니다. 레거시에서는 빈 문자열도 값이 없는 경우와 동일하게 처리하므로 이 동작을 그대로 유지합니다.
 */
public record UpdateWorkspaceCommand(
    UUID workspaceId, String name, String description, String slug) {}
