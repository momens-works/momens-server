package works.momens.server.workspace.email;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class InvitationEmailConfigTest {

  @Test
  void startsWithResendWithoutRestClientAutoConfiguration() {
    new ApplicationContextRunner()
        .withUserConfiguration(InvitationEmailConfig.class)
        .withPropertyValues(
            "momens.workspace.invitation.email.provider=resend",
            "momens.workspace.invitation.email.accept-url=https://app.example.com/invite",
            "momens.workspace.invitation.email.from=invite@example.com",
            "momens.workspace.invitation.email.reply-to=reply@example.com",
            "momens.workspace.invitation.email.resend.api-key=test-key")
        .run(context -> {
          assertThat(context).hasNotFailed().hasSingleBean(EmailClient.class);
          assertThat(context.getBean(EmailClient.class)).isInstanceOf(ResendEmailClient.class);
        });
  }
}
