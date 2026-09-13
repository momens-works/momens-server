package works.momens.server.support.persistence;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import works.momens.server.mobile.MobilePriority;
import works.momens.server.mobile.board.BoardStatus;
import works.momens.server.project.task.TaskOrigin;
import works.momens.server.project.task.TaskStatus;
import works.momens.server.workspace.invitation.InvitationStatus;
import works.momens.server.workspace.membership.WorkspaceRole;

/**
 * CHECK 제약과 enum이 같은 값 집합을 나타낸다는 대응 관계를 선언합니다.
 *
 * <p>기존에는 어떤 컬럼의 허용 값이 어떤 enum과 일치해야 하는지가 코드로 드러나지 않고 주석에만 기록되어 있었습니다. 이 파일은 해당 관계를 검증 가능한 선언으로 옮긴
 * 것이며, {@code CheckConstraintEnumConsistencyTest}가 선언 내용과 실제 스키마를 대조합니다.
 *
 * <p>값 집합을 제한하는 모든 CHECK 제약은 아래 두 목록 중 하나에 등록되어야 합니다. 새 컬럼을 추가하고 등록하지 않으면 테스트가 실패하므로 이관 과정에서 컬럼이
 * 늘어나도 검증 대상에서 누락되지 않습니다.
 *
 * <p>완전성은 DB의 CHECK 제약에 대해서만 보장합니다. CHECK 제약은 DB에서 빠짐없이 조회할 수 있지만, 어떤 enum이 특정 컬럼의 값 집합을 나타내는지는
 * 자동으로 판정할 수 없습니다. 따라서 기존 컬럼과 같은 값 집합을 나타내는 enum을 새로 만들고 이 목록에 등록하지 않으면 해당 enum은 검증 대상에 포함되지 않습니다.
 *
 * <p>같은 값 집합을 나타내는 enum이 여러 개이면 어떤 enum을 연결했는지까지는 검증하지 않습니다. {@code projects.health_status}와 {@code
 * milestones.health_status}가 해당합니다. {@code MOM-0887}에서 {@code HealthStatus}를 {@code
 * project.core.internal}과 {@code project.milestone.internal}에 같은 값으로 각각 정의했으므로 두 컬럼의 enum 연결을 서로
 * 바꾸어도 테스트가 통과합니다.
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
              storedValues(WorkspaceRole.values(), WorkspaceRole::value),
              IntendedDifference.onlyInEnum("owner는 워크스페이스 생성 시 결정되므로 초대로 부여할 수 없습니다", "owner")),
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
              "status",
              storedValues(BoardStatus.values(), BoardStatus::key),
              IntendedDifference.NONE),
          new EnumLink(
              "tasks",
              "origin_type",
              storedValues(TaskOrigin.values(), TaskOrigin::value),
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
              storedValues(
                  works.momens.server.minsu.Role.values(), works.momens.server.minsu.Role::value),
              IntendedDifference.NONE),
          new EnumLink(
              "milestones",
              "health_status",
              internalEnumValues(
                  "works.momens.server.project.milestone.internal.HealthStatus", "value"),
              IntendedDifference.NONE),
          new EnumLink(
              "projects",
              "health_status",
              internalEnumValues("works.momens.server.project.core.internal.HealthStatus", "value"),
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
              IntendedDifference.NONE));

  /**
   * 값 집합 전체를 선언하는 enum이 없는 컬럼의 목록입니다. 저장하는 값 하나만 상수로 선언한 경우도 있지만, 상수 하나는 값 집합이 아니라 원소이므로 대조하지 않습니다.
   */
  static final List<ColumnWithoutEnum> COLUMNS_WITHOUT_ENUM =
      List.of(
          new ColumnWithoutEnum(
              "blockers", "blocked_entity_type", "레거시가 소유하는 테이블이며 이 서버에는 쓰기 경로가 없습니다"),
          new ColumnWithoutEnum("blockers", "status", "레거시가 소유하는 테이블이며 이 서버에는 쓰기 경로가 없습니다"),
          new ColumnWithoutEnum(
              "confirmed_memories",
              "status",
              "MemoryWriterImpl이 저장하는 값만 상수로 선언하며 값 집합 전체를 나타내는 enum은 없습니다"),
          new ColumnWithoutEnum(
              "memory_candidates",
              "status",
              "MemoryWriterImpl이 저장하는 값만 상수로 선언하며 값 집합 전체를 나타내는 enum은 없습니다"),
          new ColumnWithoutEnum(
              "milestones", "status", "Milestone 엔티티는 기본값인 planned만 사용하며 나머지 값은 레거시가 저장합니다"),
          new ColumnWithoutEnum(
              "outbox_events", "issued_by", "이 서버는 api-server만 저장하며 worker는 momens-worker가 저장합니다"),
          new ColumnWithoutEnum(
              "projects", "status", "마이그레이션 기본값인 active만 사용하며 코드에는 값 집합을 나타내는 enum이 없습니다"),
          new ColumnWithoutEnum("push_installations", "platform", "허용 값이 하나이므로 상수와 요청 검증으로만 관리합니다"),
          new ColumnWithoutEnum("signals", "type", "worker가 생성하는 원본 데이터이며 이 서버는 읽기만 합니다"),
          new ColumnWithoutEnum(
              "source_connections",
              "status",
              "SourceInstallerImpl이 저장하는 ACTIVE와 PENDING만 상수로 선언합니다"),
          new ColumnWithoutEnum(
              "task_updates",
              "kind",
              "TaskUpdateWriterImpl의 switch가 두 값을 처리하며 값 집합을 나타내는 enum은 없습니다"),
          new ColumnWithoutEnum(
              "user_identities", "provider", "허용 값이 하나이므로 UserService.PROVIDER_GOOGLE 상수로만 관리합니다"));

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
    return new StoredValues(constants[0].getDeclaringClass().getSimpleName(), values);
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
      return new StoredValues(type.getSimpleName(), values);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(className + "." + accessorName, e);
    }
  }

  /** enum 하나가 DB에 저장하는 값의 집합입니다. */
  record StoredValues(String enumName, Set<String> values) {}

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

  /** 값 집합 전체를 선언하는 enum이 없는 컬럼입니다. */
  record ColumnWithoutEnum(String table, String column, String reason) {

    ColumnWithoutEnum {
      if (reason.isBlank()) {
        throw new IllegalArgumentException("대조하지 않는 컬럼에는 근거를 작성해야 합니다.");
      }
    }
  }
}
