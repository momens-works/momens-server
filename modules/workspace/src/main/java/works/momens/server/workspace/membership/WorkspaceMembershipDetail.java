package works.momens.server.workspace.membership;

import java.time.Instant;
import java.util.UUID;

/**
 * workspace 멤버십 한 건의 상세 조회 결과.
 *
 * <p>멤버 목록 응답처럼 역할과 가입 시각이 필요한 호출자가 사용합니다. 사용자 식별자만 필요한 호출자는 {@code
 * WorkspaceMembershipReader.listMemberUserIds}가 반환하는 목록을 사용합니다.
 *
 * <p>이름과 이메일 등 사용자 정보는 user 모듈이 소유하므로 포함하지 않습니다. 멤버십과 사용자 정보를 조합하는 책임은 호출하는 쪽에 있습니다.
 */
public record WorkspaceMembershipDetail(
    UUID userId, String role, Instant createdAt, Instant updatedAt) {}
