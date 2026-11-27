package works.momens.server.workspace.invitation.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import works.momens.server.common.api.BusinessException;
import works.momens.server.common.persistence.JpaAuditingConfig;
import works.momens.server.common.test.AbstractPostgresIntegrationTest;
import works.momens.server.user.UserProfile;
import works.momens.server.user.UserService;
import works.momens.server.workspace.WorkspaceSeedSql;
import works.momens.server.workspace.core.WorkspaceDetail;
import works.momens.server.workspace.core.WorkspaceReader;
import works.momens.server.workspace.email.InvitationEmail;
import works.momens.server.workspace.email.InvitationEmailSender;
import works.momens.server.workspace.invitation.AcceptInvitationCommand;
import works.momens.server.workspace.invitation.CreateInvitationCommand;
import works.momens.server.workspace.invitation.InvitationStatus;
import works.momens.server.workspace.invitation.WorkspaceInvitationAcceptor;
import works.momens.server.workspace.invitation.WorkspaceInvitationDetail;
import works.momens.server.workspace.invitation.WorkspaceInvitationWriter;
import works.momens.server.workspace.membership.AddMembershipCommand;
import works.momens.server.workspace.membership.AssignableWorkspaceRole;
import works.momens.server.workspace.membership.MembershipErrorCode;
import works.momens.server.workspace.membership.WorkspaceMembershipDetail;
import works.momens.server.workspace.membership.WorkspaceMembershipWriter;
import works.momens.server.workspace.membership.WorkspaceRole;

/**
 * 초대 생성과 수락에서 기존 멤버를 판정하는 시점을 검증합니다.
 *
 * <p>워크스페이스 조회와 멤버십 저장은 다른 하위 도메인의 public API이므로 {@code @MockitoBean}으로 교체합니다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, InvitationConfig.class})
class WorkspaceInvitationIntegrationTest extends AbstractPostgresIntegrationTest {

  private static final String EMAIL = "hong@momens.works";

  @Autowired private WorkspaceInvitationWriter invitationWriter;
  @Autowired private WorkspaceInvitationAcceptor invitationAcceptor;
  @Autowired private WorkspaceInvitationRepository invitationRepository;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private TestEntityManager entityManager;

  @MockitoBean private WorkspaceReader workspaceReader;
  @MockitoBean private WorkspaceMembershipWriter membershipWriter;
  @MockitoBean private UserService userService;
  @MockitoBean private InvitationEmailSender emailSender;

  @Test
  @DisplayName("초대 생성은 기존 멤버를 판정하지 않고, 수락은 수락한 사용자 ID로 멤버십을 추가한다")
  void acceptAddsMembershipByAcceptingUserId() {
    UUID workspaceId = WorkspaceSeedSql.insertWorkspace(entityManager, "invite-accept");
    UUID inviterId = WorkspaceSeedSql.insertUser(entityManager, "admin@momens.works");
    UUID inviteeId = WorkspaceSeedSql.insertUser(entityManager, EMAIL);
    stubWorkspace(workspaceId);
    stubProfile(inviterId, "admin@momens.works");
    stubProfile(inviteeId, EMAIL);
    when(membershipWriter.addIfAbsent(any()))
        .thenReturn(
            Optional.of(
                new WorkspaceMembershipDetail(inviteeId, "member", Instant.now(), Instant.now())));

    WorkspaceInvitationDetail invitation = create(workspaceId, inviterId);
    invitationAcceptor.accept(new AcceptInvitationCommand(inviteeId, sentToken()));

    assertThat(invitation.status()).isEqualTo(InvitationStatus.PENDING);
    verify(membershipWriter)
        .addIfAbsent(new AddMembershipCommand(workspaceId, inviteeId, WorkspaceRole.MEMBER));
  }

  /**
   * 수락이 실패하면 초대 수락 상태 반영이 롤백되는지 확인합니다. 롤백을 확인하려면 수락이 독립 트랜잭션에서 실행되어야 하므로 테스트 메서드는
   * 비트랜잭션(NOT_SUPPORTED)으로 두고, 준비한 행은 직접 정리합니다.
   */
  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  @DisplayName("멤버십 추가가 거부되면 WORKSPACE_MEMBER_ALREADY_EXISTS를 던지고 초대는 대기 상태로 남는다")
  void rejectedMembershipKeepsInvitationPending() {
    TransactionTemplate tx = new TransactionTemplate(transactionManager);
    UUID workspaceId =
        tx.execute(status -> WorkspaceSeedSql.insertWorkspace(entityManager, "invite-member"));
    UUID inviterId =
        tx.execute(status -> WorkspaceSeedSql.insertUser(entityManager, "admin@momens.works"));
    UUID inviteeId = tx.execute(status -> WorkspaceSeedSql.insertUser(entityManager, EMAIL));
    try {
      stubWorkspace(workspaceId);
      stubProfile(inviterId, "admin@momens.works");
      stubProfile(inviteeId, EMAIL);
      when(membershipWriter.addIfAbsent(any())).thenReturn(Optional.empty());
      WorkspaceInvitationDetail invitation = create(workspaceId, inviterId);

      assertThatThrownBy(
              () -> invitationAcceptor.accept(new AcceptInvitationCommand(inviteeId, sentToken())))
          .isInstanceOf(BusinessException.class)
          .extracting(e -> ((BusinessException) e).getErrorCode())
          .isEqualTo(MembershipErrorCode.WORKSPACE_MEMBER_ALREADY_EXISTS);
      String stored =
          tx.execute(
              status -> invitationRepository.findById(invitation.id()).orElseThrow().getStatus());
      assertThat(stored).isEqualTo(InvitationStatus.PENDING.value());
    } finally {
      tx.executeWithoutResult(status -> {
        entityManager
            .getEntityManager()
            .createNativeQuery("DELETE FROM workspaces WHERE id = ?1")
            .setParameter(1, workspaceId)
            .executeUpdate();
        entityManager
            .getEntityManager()
            .createNativeQuery("DELETE FROM users WHERE id IN (?1, ?2)")
            .setParameter(1, inviterId)
            .setParameter(2, inviteeId)
            .executeUpdate();
      });
    }
  }

  private WorkspaceInvitationDetail create(UUID workspaceId, UUID inviterId) {
    return invitationWriter.create(
        new CreateInvitationCommand(workspaceId, inviterId, EMAIL, AssignableWorkspaceRole.MEMBER));
  }

  private String sentToken() {
    ArgumentCaptor<InvitationEmail> email = ArgumentCaptor.forClass(InvitationEmail.class);
    verify(emailSender).send(email.capture());
    return email.getValue().rawToken();
  }

  private void stubWorkspace(UUID workspaceId) {
    when(workspaceReader.findById(workspaceId))
        .thenReturn(
            Optional.of(
                new WorkspaceDetail(
                    workspaceId, "모멘스", "momens", null, Instant.now(), Instant.now())));
  }

  private void stubProfile(UUID userId, String email) {
    when(userService.getProfile(userId))
        .thenReturn(
            new UserProfile(userId, email, "홍길동", null, null, Instant.now(), Instant.now()));
  }
}
