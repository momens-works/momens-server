package works.momens.server.project.task.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/**
 * 태스크 열린질문.
 *
 * <p>민수가 적재하는 {@code task_open_questions}를 읽기 전용으로 매핑합니다(@Immutable). 민수 구현 전에는 같은 backing 계약을 따르는
 * fixture가 채웁니다(ADR-0011). 이 서버가 쓰지 않으므로 식별자를 앱에서 만들지 않고 감사 필드도 두지 않아 {@code BaseEntity}를 상속하지
 * 않습니다. 완료기준 항목({@code TaskChecklistItem})은 수정 화면이 저장하는 값이라 반대로 이 서버가 소유합니다.
 *
 * <p>{@code body}는 생산 단계에서 공백 포함 50자 이하입니다(2026-07-08 화면설계서 task_002 8번). 이 서버는 자르거나 생성하지 않고 저장된 값을
 * 그대로 읽습니다.
 */
@Getter
@Entity
@Immutable
@Table(name = "task_open_questions")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class TaskOpenQuestion {

  @Id
  @Column(columnDefinition = "uuid")
  private UUID id;

  @Column(nullable = false)
  private String body;

  @Column(name = "sort_order", nullable = false)
  private int sortOrder;
}
