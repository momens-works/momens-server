package works.momens.server.workspace.core;

import java.util.UUID;

/** 요청자가 새 워크스페이스의 owner가 됩니다. slug는 앞뒤 공백을 제거한 뒤 비어 있으면 이름으로 생성하고, 그 외에는 형식·예약어·중복을 검증합니다. */
public record CreateWorkspaceCommand(
    UUID requesterId, String name, String description, String slug) {}
