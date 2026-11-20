package works.momens.server.mobile.roster.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;
import works.momens.server.mobile.roster.ProjectMember;

/**
 * {@code GET /api/mobile/projects/{projectId}/members} 응답. 형식은 docs/spec/openapi.json에서 확인합니다.
 *
 * <p>신규 Standard 계약이라 {@code avatar_url}은 값이 없어도 null로 항상 포함합니다(bootstrap 전례).
 */
@Schema(description = "프로젝트 멤버 목록 응답")
public record ProjectMembersResponse(
    @Schema(description = "멤버 목록(이름 오름차순). 검색 결과가 없으면 빈 배열입니다.") List<MemberResponse> members) {

  @Schema(description = "프로젝트 멤버 한 명")
  public record MemberResponse(
      @Schema(description = "사용자 식별자", example = "5d2f7f3a-5db1-4f2c-8b9e-13607dd1f5e8") UUID id,
      @Schema(description = "이름", example = "김민지") String name,
      @Schema(description = "아바타 URL. 미설정이면 null로 포함됩니다.", nullable = true) String avatarUrl) {}

  public static ProjectMembersResponse from(List<ProjectMember> members) {
    return new ProjectMembersResponse(
        members.stream()
            .map(member -> new MemberResponse(member.id(), member.name(), member.avatarUrl()))
            .toList());
  }
}
