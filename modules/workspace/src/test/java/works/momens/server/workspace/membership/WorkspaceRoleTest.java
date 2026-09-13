package works.momens.server.workspace.membership;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 역할 간 서열과 저장 값 해석 규칙을 검증합니다. */
class WorkspaceRoleTest {

  @Test
  @DisplayName("역할 서열은 owner, admin, member 순이다")
  void ownerOutranksAdminAndAdminOutranksMember() {
    assertThat(WorkspaceRole.OWNER.isAtLeast(WorkspaceRole.ADMIN)).isTrue();
    assertThat(WorkspaceRole.ADMIN.isAtLeast(WorkspaceRole.ADMIN)).isTrue();
    assertThat(WorkspaceRole.MEMBER.isAtLeast(WorkspaceRole.ADMIN)).isFalse();
    assertThat(WorkspaceRole.MEMBER.isAtLeast(WorkspaceRole.MEMBER)).isTrue();
  }

  @Test
  @DisplayName("세 가지 저장 값을 각각의 역할로 변환한다")
  void fromMapsStoredValues() {
    assertThat(WorkspaceRole.from("owner")).contains(WorkspaceRole.OWNER);
    assertThat(WorkspaceRole.from("admin")).contains(WorkspaceRole.ADMIN);
    assertThat(WorkspaceRole.from("member")).contains(WorkspaceRole.MEMBER);
  }

  @Test
  @DisplayName("정의되지 않은 저장 값은 빈 Optional로 반환한다")
  void fromIsEmptyForUnknownValue() {
    assertThat(WorkspaceRole.from("OWNER")).isEmpty();
    assertThat(WorkspaceRole.from("viewer")).isEmpty();
    assertThat(WorkspaceRole.from(null)).isEmpty();
  }
}
