package works.momens.server.workspace.invitation.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class InvitationTokenTest {

  // 기대값은 레거시의 hashInviteToken(momens-api/internal/workspace/service.go:625)을
  // 직접 실행해 얻은 값입니다.
  @Test
  void hashesTokenExactlyAsLegacyDoes() {
    assertThat(InvitationToken.hash("momens-invite-token-fixture"))
        .isEqualTo("47bf37d31bc2370173561234d07d827dbebabf29684884a983813aba5eba7044");
  }
}
