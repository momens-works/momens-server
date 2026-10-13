package works.momens.server.workspace.core;

import java.util.UUID;

/** 요청자가 새 워크스페이스의 owner가 됩니다. slug를 생략하면 이름으로 생성하고, 직접 입력한 slug는 보정하지 않고 검증합니다. */
public record CreateWorkspaceCommand(
    UUID requesterId, String name, String description, String slug) {}
