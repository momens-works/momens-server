package works.momens.server.support.persistence;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import works.momens.server.mcp.grant.McpScope;
import works.momens.server.mobile.MobilePriority;
import works.momens.server.notification.PushInstallationPlatform;
import works.momens.server.project.core.ProjectHealthStatus;
import works.momens.server.project.milestone.MilestoneHealthStatus;
import works.momens.server.project.task.TaskOrigin;
import works.momens.server.project.task.TaskPriority;
import works.momens.server.project.task.TaskRole;
import works.momens.server.project.task.TaskStatus;
import works.momens.server.project.taskupdate.TaskUpdateKind;
import works.momens.server.signal.SignalType;
import works.momens.server.source.connection.SourceConnectionStatus;
import works.momens.server.user.UserIdentityProvider;
import works.momens.server.workspace.invitation.InvitationStatus;
import works.momens.server.workspace.membership.AssignableWorkspaceRole;
import works.momens.server.workspace.membership.WorkspaceRole;

/**
 * CHECK 제약과 enum이 같은 값 집합을 나타낸다는 대응 관계를 선언합니다.
 *
 * <p>기존에는 어떤 컬럼의 허용 값이 어떤 enum과 일치해야 하는지가 코드로 드러나지 않고 주석에만 기록되어 있었습니다. 이 파일은 해당 관계를 검증 가능한 선언으로 옮긴
 * 것이며, {@code CheckConstraintEnumConsistencyTest}가 선언 내용과 실제 스키마를 대조합니다.
 *
 * <p>값 집합을 제한하는 모든 CHECK 제약은 {@link #ENUM_LINKS}에 등록해야 합니다. 각 컬럼에는 해당 테이블을 소유한 모듈의 도메인 enum을 하나
 * 연결하고, 다른 모듈의 enum은 값 집합이 의도적으로 다를 때만 함께 연결합니다. 새 컬럼을 추가한 뒤 등록하지 않으면 테스트가 실패하므로, 이관 과정에서 컬럼이
 * 늘어나더라도 검증 대상에서 누락되지 않습니다.
 *
 * <p>완전성은 DB의 CHECK 제약을 기준으로 보장합니다. 같은 값 집합을 포함하는 enum을 새로 만들고 이 목록에 등록하지 않으면 해당 enum 파일은
 * `ValueSetLiteralTest`에서 실패합니다. 하지만 값이 하나뿐인 enum은 검사하지 않습니다.
 *
 * <p>같은 값 집합을 표현하는 enum이 여러 개이면 어떤 enum을 연결했는지까지는 검증하지 않습니다. {@code projects.health_status}와 {@code
 * milestones.health_status}, {@code tasks.role}과 {@code
 * minsu_task_draft_generations.baseline_role}이 이에 해당합니다. 각 쌍에 연결된 두 enum은 값이 같으므로 서로 바꾸어 연결해도 테스트가
 * 통과합니다.
 *
 * <p>두 enum 중 하나의 값 집합이 달라지면 테스트는 실패하지만, 실패 메시지가 가리키는 컬럼은 실제 소유 관계와 다를 수 있습니다. 연결된 enum이 해당 테이블을
 * 소유한 모듈에 속하는지까지 검증하려면 테이블과 모듈의 대응 관계를 별도로 선언해야 합니다. 해당 선언도 수동으로 관리해야 하는 또 다른 대응 목록이 되므로 추가하지 않습니다.
 */
final class CheckConstraintEnumLinks {

  /** CHECK 제약과 같은 값 집합을 나타내는 enum의 목록입니다. 하나의 컬럼에 여러 enum이 대응할 수 있으므로 항목 수가 컬럼 수보다 많을 수 있습니다. */
  static final List<EnumLink> ENUM_LINKS =
      List.of(
          new EnumLink(
              "workspace_members",
              "role",
              storedValues(WorkspaceRole.values(), WorkspaceRole::value),
              IntendedDifference.NONE),
          new EnumLink(
              "workspace_invitations",
              "role",
              storedValues(AssignableWorkspaceRole.values(), AssignableWorkspaceRole::value),
              IntendedDifference.NONE),
          new EnumLink(
              "workspace_invitations",
              "status",
              storedValues(InvitationStatus.values(), InvitationStatus::value),
              IntendedDifference.onlyInEnum("만료 상태는 저장하지 않고 expires_at을 기준으로 계산합니다", "expired")),
          new EnumLink(
              "workspace_label_sequences",
              "label_prefix",
              internalEnumValues(
                  "works.momens.server.workspace.label.internal.LabelPrefix", "value"),
              IntendedDifference.NONE),
          new EnumLink(
              "tasks",
              "status",
              storedValues(TaskStatus.values(), TaskStatus::value),
              IntendedDifference.NONE),
          new EnumLink(
              "tasks",
              "origin_type",
              storedValues(TaskOrigin.values(), TaskOrigin::value),
              IntendedDifference.NONE),
          new EnumLink(
              "tasks",
              "priority",
              storedValues(TaskPriority.values(), TaskPriority::value),
              IntendedDifference.NONE),
          new EnumLink(
              "tasks",
              "priority",
              storedValues(MobilePriority.values(), MobilePriority::key),
              IntendedDifference.onlyInConstraint("모바일은 urgent를 high로 해석합니다", "urgent")),
          new EnumLink(
              "tasks",
              "priority",
              storedValues(
                  works.momens.server.minsu.Priority.values(),
                  works.momens.server.minsu.Priority::value),
              IntendedDifference.onlyInConstraint("minsu가 공개하는 우선순위는 세 가지입니다", "urgent")),
          new EnumLink(
              "tasks",
              "role",
              storedValues(TaskRole.values(), TaskRole::value),
              IntendedDifference.NONE),
          new EnumLink(
              "milestones",
              "health_status",
              storedValues(MilestoneHealthStatus.values(), MilestoneHealthStatus::value),
              IntendedDifference.NONE),
          new EnumLink(
              "milestones",
              "status",
              internalEnumValues(
                  "works.momens.server.project.milestone.internal.MilestoneStatus", "value"),
              IntendedDifference.NONE),
          new EnumLink(
              "projects",
              "health_status",
              storedValues(ProjectHealthStatus.values(), ProjectHealthStatus::value),
              IntendedDifference.NONE),
          new EnumLink(
              "signal_actions",
              "action_type",
              internalEnumValues("works.momens.server.signal.action.SignalActionType", "value"),
              IntendedDifference.NONE),
          new EnumLink(
              "push_deliveries",
              "status",
              internalEnumValues(
                  "works.momens.server.notification.dispatch.DeliveryStatus", "value"),
              IntendedDifference.NONE),
          new EnumLink(
              "minsu_task_draft_generations",
              "status",
              internalEnumValues(
                  "works.momens.server.minsu.draft.ledger.GenerationStatus", "value"),
              IntendedDifference.NONE),
          new EnumLink(
              "minsu_task_draft_generations",
              "completion_reason",
              internalEnumValues(
                  "works.momens.server.minsu.draft.ledger.CompletionReason", "value"),
              IntendedDifference.NONE),
          new EnumLink(
              "minsu_task_draft_generations",
              "baseline_priority",
              storedValues(
                  works.momens.server.minsu.Priority.values(),
                  works.momens.server.minsu.Priority::value),
              IntendedDifference.onlyInConstraint(
                  "CHECK 제약은 생산자인 minsu가 아니라 tasks 계약을 기준으로 합니다", "urgent")),
          new EnumLink(
              "minsu_task_draft_generations",
              "baseline_role",
              storedValues(
                  works.momens.server.minsu.Role.values(), works.momens.server.minsu.Role::value),
              IntendedDifference.NONE),
          new EnumLink(
              "projects",
              "status",
              internalEnumValues(
                  "works.momens.server.project.core.internal.ProjectStatus", "value"),
              IntendedDifference.NONE),
          new EnumLink(
              "signals",
              "type",
              storedValues(SignalType.values(), SignalType::value),
              IntendedDifference.NONE),
          new EnumLink(
              "outbox_events",
              "issued_by",
              internalEnumValues("works.momens.server.outbox.internal.OutboxEventIssuer", "value"),
              IntendedDifference.NONE),
          new EnumLink(
              "push_installations",
              "platform",
              storedValues(PushInstallationPlatform.values(), PushInstallationPlatform::value),
              IntendedDifference.NONE),
          new EnumLink(
              "user_identities",
              "provider",
              storedValues(UserIdentityProvider.values(), UserIdentityProvider::value),
              IntendedDifference.NONE),
          new EnumLink(
              "memory_candidates",
              "status",
              internalEnumValues(
                  "works.momens.server.memory.internal.MemoryCandidateStatus", "value"),
              IntendedDifference.NONE),
          new EnumLink(
              "confirmed_memories",
              "status",
              internalEnumValues(
                  "works.momens.server.memory.internal.ConfirmedMemoryStatus", "value"),
              IntendedDifference.NONE),
          new EnumLink(
              "blockers",
              "status",
              internalEnumValues(
                  "works.momens.server.project.blocker.internal.BlockerStatus", "value"),
              IntendedDifference.NONE),
          new EnumLink(
              "blockers",
              "blocked_entity_type",
              internalEnumValues(
                  "works.momens.server.project.blocker.internal.BlockedEntityType", "value"),
              IntendedDifference.NONE),
          new EnumLink(
              "task_updates",
              "kind",
              storedValues(TaskUpdateKind.values(), TaskUpdateKind::value),
              IntendedDifference.NONE),
          new EnumLink(
              "source_connections",
              "status",
              storedValues(SourceConnectionStatus.values(), SourceConnectionStatus::value),
              IntendedDifference.NONE),
          new EnumLink(
              "mcp_grants",
              "scopes",
              storedValues(McpScope.values(), McpScope::value),
              IntendedDifference.NONE));

  private CheckConstraintEnumLinks() {}

  /**
   * app 모듈에서 타입 이름으로 참조할 수 있는 enum의 저장 값 집합을 수집합니다.
   *
   * <p>저장 값을 반환하는 메서드 이름이 통일되어 있지 않으므로 접근자를 인자로 받습니다. 대부분 {@code value()}를 사용하지만 모바일 enum은 {@code
   * key()}를 사용합니다. 접근자 이름을 하나로 가정하면 다른 이름을 사용하는 enum이 검증 대상에서 누락될 수 있습니다.
   */
  static <E extends Enum<E>> StoredValues storedValues(
      E[] constants, Function<E, String> storedValue) {
    Set<String> values = new LinkedHashSet<>();
    Arrays.stream(constants).map(storedValue).forEach(values::add);
    return new StoredValues(constants[0].getDeclaringClass().getName(), values);
  }

  /**
   * 모듈 내부에 있어 app 모듈에서 타입 이름으로 참조할 수 없는 enum의 저장 값 집합을 수집합니다.
   *
   * <p>Spring Modulith 기준으로 공개되지 않은 패키지의 enum이므로 전체 클래스 이름과 접근자 이름을 문자열로 받습니다. 클래스나 메서드 이름이 변경되면 해당
   * 위치에서 실패하므로 문자열로 참조하더라도 검증 대상에서 누락되지 않습니다.
   */
  static StoredValues internalEnumValues(String className, String accessorName) {
    try {
      Class<?> type = Class.forName(className);
      Method accessor = type.getDeclaredMethod(accessorName);
      accessor.setAccessible(true);
      Set<String> values = new LinkedHashSet<>();
      for (Object constant : type.getEnumConstants()) {
        values.add((String) accessor.invoke(constant));
      }
      return new StoredValues(type.getName(), values);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(className + "." + accessorName, e);
    }
  }

  /** enum 하나가 DB에 저장하는 값의 집합입니다. */
  record StoredValues(String enumClassName, Set<String> values) {}

  /**
   * 두 값 집합 사이의 의도된 차이를 정확히 선언합니다.
   *
   * <p>단순히 값 집합이 달라도 된다고 허용하는 것이 아니라 어느 값이 어느 쪽에만 존재하는지를 명시합니다. 실제 차이가 선언과 다르면 테스트가 실패합니다. CHECK
   * 제약에 허용 값이 추가되는 등 값 집합이 변경되면 차이도 달라지므로 변경 시점에 확인할 수 있습니다.
   */
  record IntendedDifference(Set<String> constraintOnly, Set<String> enumOnly, String reason) {

    static final IntendedDifference NONE = new IntendedDifference(Set.of(), Set.of(), "");

    IntendedDifference {
      if ((!constraintOnly.isEmpty() || !enumOnly.isEmpty()) && reason.isBlank()) {
        throw new IllegalArgumentException("의도된 차이에는 근거를 작성해야 합니다.");
      }
    }

    static IntendedDifference onlyInConstraint(String reason, String... values) {
      return new IntendedDifference(Set.of(values), Set.of(), reason);
    }

    static IntendedDifference onlyInEnum(String reason, String... values) {
      return new IntendedDifference(Set.of(), Set.of(values), reason);
    }
  }

  /** 컬럼 하나와 enum 하나의 대응 관계입니다. */
  record EnumLink(
      String table, String column, StoredValues storedValues, IntendedDifference difference) {}
}
