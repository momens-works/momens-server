package works.momens.server.workspace.membership;

import java.util.UUID;

/**
 * 사용자가 가진 workspace 멤버십 한 건의 조회 결과.
 *
 * <p>이 record는 사용자가 어느 workspace에 어떤 역할로 속해 있는지 조회한 사용자 기준 조회 결과입니다. workspace 기준 조회 결과는 {@link
 * WorkspaceMembershipDetail}로 나타냅니다. 모바일 bootstrap이 project의 role을 소속 workspace 멤버십 role로 매핑할 때
 * 사용합니다(MOM-60).
 *
 * <p>{@code role}은 멤버십 역할({@code owner}/{@code admin}/{@code member})입니다. task의 기능 역할(pm, android
 * 등)과는 구분되는 개념입니다.
 */
public record UserWorkspaceMembership(UUID workspaceId, String role) {}
