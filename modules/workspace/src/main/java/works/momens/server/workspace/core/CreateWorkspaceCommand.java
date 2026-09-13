package works.momens.server.workspace.core;

import java.util.UUID;

/**
 * 워크스페이스 생성에 필요한 입력값입니다.
 *
 * <p>{@code requesterId}는 요청을 보낸 사용자이며, 생성한 워크스페이스에 owner 역할의 멤버로 등록됩니다. {@code
 * CreateProjectCommand}에도 같은 이름의 필드가 있지만 프로젝트 생성에서는 소유자를 지정하지 않았을 때만 기본값으로 사용합니다. 워크스페이스 생성에서는 요청자가
 * 항상 owner가 됩니다.
 *
 * <p>{@code slug}를 전달하지 않으면 {@code name}을 기준으로 생성합니다. 직접 전달한 경우에는 형식, 예약어, 중복 여부를 검증하며, 하나라도 규칙에 맞지
 * 않으면 요청을 거부합니다.
 */
public record CreateWorkspaceCommand(
    UUID requesterId, String name, String description, String slug) {}
