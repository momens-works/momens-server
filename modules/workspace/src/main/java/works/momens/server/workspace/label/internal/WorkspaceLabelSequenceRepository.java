package works.momens.server.workspace.label.internal;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface WorkspaceLabelSequenceRepository
    extends JpaRepository<WorkspaceLabelSequence, WorkspaceLabelSequenceId> {

  /**
   * workspace와 prefix 조합의 다음 번호를 원자적으로 발급하고 그 번호를 반환합니다.
   *
   * <p>레거시 {@code next_workspace_label} 함수와 같은 방식입니다. 행이 없으면 {@code next_value = 2}로 INSERT하고 1을
   * 발급하며, 있으면 {@code next_value}를 1 올리고 직전 값을 발급합니다. {@code INSERT ... ON CONFLICT DO UPDATE}는 한
   * 문장이라 같은 행에 동시 발급이 들어와도 행 잠금으로 직렬화되어 번호가 겹치지 않습니다.
   *
   * <p>{@code @Modifying}을 붙이지 않는 이유는 {@code RETURNING} 값을 받아야 하기 때문입니다(붙이면 반환이 {@code void/int}로
   * 제한됨).
   */
  @Query(
      value =
          """
          INSERT INTO workspace_label_sequences (workspace_id, label_prefix, next_value, updated_at)
          VALUES (:workspaceId, :prefix, 2, now())
          ON CONFLICT (workspace_id, label_prefix) DO UPDATE
            SET next_value = workspace_label_sequences.next_value + 1,
                updated_at = now()
          RETURNING next_value - 1
          """,
      nativeQuery = true)
  long allocate(@Param("workspaceId") UUID workspaceId, @Param("prefix") String prefix);
}
