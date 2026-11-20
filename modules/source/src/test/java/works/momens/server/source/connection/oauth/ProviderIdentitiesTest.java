package works.momens.server.source.connection.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ProviderIdentitiesTest {

  @Test
  @DisplayName("GitHub은 사용자 정보 응답에서 외부 계정 정보를 추출한다")
  void readsGithubIdentityFromTheUserEndpointBody() {
    ProviderIdentity identity =
        ProviderIdentities.github(
            Map.of(),
            Map.of(
                "id",
                4711,
                "login",
                "jsshin",
                "name",
                "신진수",
                "html_url",
                "https://github.com/jsshin"));

    assertThat(identity.externalId()).isEqualTo("jsshin");
    assertThat(identity.externalName()).isEqualTo("신진수");
    assertThat(identity.metadata())
        .containsEntry("github_user_id", 4711)
        .containsEntry("login", "jsshin");
  }

  @Test
  @DisplayName("GitHub 계정에 이름이 없으면 로그인 ID를 이름으로 사용한다")
  void fallsBackToGithubLoginWhenTheAccountHasNoName() {
    ProviderIdentity identity =
        ProviderIdentities.github(Map.of(), Map.of("id", 1, "login", "jsshin"));

    assertThat(identity.externalName()).isEqualTo("jsshin");
  }

  @Test
  @DisplayName("GitHub 응답에 로그인 ID가 없으면 거부한다")
  void rejectsGithubIdentityWithoutLogin() {
    assertThatThrownBy(() -> ProviderIdentities.github(Map.of(), Map.of("id", 1)))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("Slack은 인증 확인 응답에서 팀 정보를 추출한다")
  void readsSlackIdentityFromTheAuthTestBody() {
    ProviderIdentity identity =
        ProviderIdentities.slack(
            Map.of(),
            Map.of("ok", true, "team", "모먼스", "team_id", "T-1", "url", "https://momens.slack.com"));

    assertThat(identity.externalId()).isEqualTo("T-1");
    assertThat(identity.externalName()).isEqualTo("모먼스");
    assertThat(identity.metadata()).containsEntry("team_url", "https://momens.slack.com");
  }

  @Test
  @DisplayName("Slack 응답이 성공 상태가 아니면 거부한다")
  void rejectsSlackIdentityWhenTheResponseIsNotOk() {
    assertThatThrownBy(
            () -> ProviderIdentities.slack(Map.of(), Map.of("ok", false, "team_id", "T-1")))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("Notion은 토큰 응답에서 워크스페이스 정보를 추출한다")
  void readsNotionIdentityFromTheTokenResponse() {
    ProviderIdentity identity =
        ProviderIdentities.notion(
            Map.of("workspace_id", "ws-1", "workspace_name", "모먼스", "workspace_icon", "icon"),
            Map.of());

    assertThat(identity.externalId()).isEqualTo("ws-1");
    assertThat(identity.externalName()).isEqualTo("모먼스");
    assertThat(identity.metadata()).containsEntry("workspace_icon", "icon");
  }

  @Test
  @DisplayName("Notion 워크스페이스에 이름이 없으면 식별자를 이름으로 사용한다")
  void fallsBackToNotionWorkspaceIdWhenTheWorkspaceHasNoName() {
    ProviderIdentity identity = ProviderIdentities.notion(Map.of("workspace_id", "ws-1"), Map.of());

    assertThat(identity.externalName()).isEqualTo("ws-1");
  }

  @Test
  @DisplayName("Figma는 토큰 응답에서 사용자 식별자를 추출한다")
  void readsFigmaIdentityFromTheTokenResponse() {
    ProviderIdentity identity =
        ProviderIdentities.figma(Map.of("user_id_string", "9001"), Map.of());

    assertThat(identity.externalId()).isEqualTo("9001");
    assertThat(identity.externalName()).isEqualTo("Figma user 9001");
  }

  @Test
  @DisplayName("Figma 응답에 사용자 식별자가 없으면 거부한다")
  void rejectsFigmaIdentityWithoutUserId() {
    assertThatThrownBy(() -> ProviderIdentities.figma(Map.of(), Map.of()))
        .isInstanceOf(IllegalStateException.class);
  }
}
