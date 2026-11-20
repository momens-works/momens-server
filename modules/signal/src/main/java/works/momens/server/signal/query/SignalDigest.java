package works.momens.server.signal.query;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/**
 * 프로젝트의 하루 시그널 전체를 요약한 읽기 모델입니다.
 *
 * <p>날짜 컬럼 대신 생산 시각으로 거르며 하루 경계의 타임존은 호출하는 표면이 정합니다.
 */
@Getter
@Entity
@Immutable
@Table(name = "signal_digests")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class SignalDigest {

  @Id
  @Column(columnDefinition = "uuid")
  private UUID id;

  @Column(name = "workspace_id", nullable = false, columnDefinition = "uuid")
  private UUID workspaceId;

  @Column(name = "project_id", nullable = false, columnDefinition = "uuid")
  private UUID projectId;

  @Column(nullable = false)
  private String summary;

  /** 생산 시각. 브리프가 하루 범위로 거르는 기준입니다. */
  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  /** 생산자가 문단을 철회하는 유일한 경로. 조회는 소프트 삭제를 없는 것으로 취급합니다(signals 미러와 같은 규칙). */
  @Column(name = "deleted_at")
  private Instant deletedAt;
}
