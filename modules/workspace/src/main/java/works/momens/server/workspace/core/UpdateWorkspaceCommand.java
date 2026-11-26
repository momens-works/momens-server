package works.momens.server.workspace.core;

import java.util.UUID;

/**
 * 워크스페이스 수정 요청.
 *
 * <p>세 필드는 모두 선택 사항입니다. 값이 없거나 빈 문자열이면 기존 저장 값을 유지합니다.
 */
public record UpdateWorkspaceCommand(
    UUID workspaceId, String name, String description, String slug) {}
