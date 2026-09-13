package works.momens.server.workspace.invitation.internal;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import works.momens.server.user.UserService;
import works.momens.server.workspace.core.WorkspaceReader;
import works.momens.server.workspace.email.InvitationEmailSender;
import works.momens.server.workspace.invitation.WorkspaceInvitationAcceptor;
import works.momens.server.workspace.invitation.WorkspaceInvitationReader;
import works.momens.server.workspace.invitation.WorkspaceInvitationWriter;
import works.momens.server.workspace.membership.WorkspaceMembershipReader;
import works.momens.server.workspace.membership.WorkspaceMembershipWriter;

@Configuration
class InvitationConfig {

  @Bean
  WorkspaceInvitationReader workspaceInvitationReader(WorkspaceInvitationRepository repository) {
    return new WorkspaceInvitationReaderImpl(repository, Clock.systemUTC());
  }

  @Bean
  WorkspaceInvitationWriter workspaceInvitationWriter(
      WorkspaceInvitationRepository repository,
      JdbcClient jdbcClient,
      TransactionTemplate transactionTemplate,
      WorkspaceReader workspaceReader,
      WorkspaceMembershipReader workspaceMembershipReader,
      UserService userService,
      InvitationEmailSender emailSender) {
    return new WorkspaceInvitationWriterImpl(
        repository,
        new PendingInvitationUpserter(jdbcClient),
        transactionTemplate,
        workspaceReader,
        workspaceMembershipReader,
        userService,
        emailSender,
        Clock.systemUTC());
  }

  @Bean
  WorkspaceInvitationAcceptor workspaceInvitationAcceptor(
      WorkspaceInvitationRepository repository,
      WorkspaceMembershipWriter membershipWriter,
      WorkspaceReader workspaceReader,
      UserService userService) {
    return new WorkspaceInvitationAcceptorImpl(
        repository, membershipWriter, workspaceReader, userService, Clock.systemUTC());
  }
}
