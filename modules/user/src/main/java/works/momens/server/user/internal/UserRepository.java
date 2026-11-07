package works.momens.server.user.internal;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface UserRepository extends JpaRepository<User, UUID> {

  Optional<User> findByEmail(String email);

  /**
   * 사용자 행을 삽입하며, 기존 행과 충돌하면 아무 작업도 하지 않습니다. 감사 필드는 스키마 기본값으로 채웁니다.
   *
   * <p>충돌 대상 컬럼은 명시하지 않습니다. 대상을 명시하면 PostgreSQL이 해당 컬럼과 일치하는 UNIQUE 인덱스를 추론합니다. {@code MOM-0836}에서
   * {@code users.email}의 UNIQUE 제약을 제거하면 추론할 대상이 없어 쿼리가 실패합니다. {@code DO NOTHING}은 충돌 대상 생략을 허용하며,
   * 생략하면 특정 인덱스를 추론하지 않고 적용 가능한 모든 UNIQUE 제약과 인덱스의 충돌을 처리합니다.
   *
   * <p>충돌 대상을 생략하면 기본 키 충돌도 무시합니다. 식별자는 애플리케이션에서 생성하는 UUID이므로 기본 키가 충돌하지 않는다는 전제입니다.
   *
   * <p>삽입 여부는 반환하지 않습니다. 두 호출자 모두 이후 {@code findByEmail}로 저장된 행을 조회하므로 해당 호출에서 행을 삽입했는지 구분할 필요가
   * 없습니다.
   */
  @Modifying(flushAutomatically = true)
  @Query(
      value =
          """
          INSERT INTO users (id, email, name, avatar_url)
          VALUES (:id, :email, :name, :avatarUrl)
          ON CONFLICT DO NOTHING
          """,
      nativeQuery = true)
  void insertIgnoringConflict(
      @Param("id") UUID id,
      @Param("email") String email,
      @Param("name") String name,
      @Param("avatarUrl") String avatarUrl);

  /**
   * 다른 사용자가 사용 중이지 않은 경우에만 이메일을 갱신합니다.
   *
   * <p>갱신된 행 수를 반환하며, 0이면 이메일 갱신이 수행되지 않은 것입니다.
   *
   * <p>이미 다른 사용자가 사용 중인 이메일로 갱신하면 제약 조건 위반으로 트랜잭션이 중단되고, 결과적으로 로그인 자체가 실패할 수 있습니다. 이를 방지하기 위해
   * UPDATE 조건에서 다른 사용자가 이미 사용 중인 이메일은 제외하도록 처리했습니다(ADR-0016).
   *
   * <p>하지만 이 조건만으로 이메일 충돌을 완전히 막을 수는 없습니다. READ COMMITTED 격리 수준에서는 SQL 문 단위로 스냅샷이 갱신되기 때문에, NOT
   * EXISTS를 평가한 직후 다른 트랜잭션이 동일한 이메일을 커밋하면 해당 변경이 조건에 반영되지 않을 수 있습니다.
   *
   * <p>갱신 이후에는 1차 캐시에 남아 있는 값이 DB와 달라질 수 있으므로 {@code clearAutomatically}로 영속성 컨텍스트를 초기화합니다.
   *
   * <p>{@code flushAutomatically}는 이 초기화 과정과 함께 동작하므로 반드시 함께 유지해야 합니다. 호출 전에 변경된 프로필 이미지가 아직
   * flush되지 않은 상태에서 영속성 컨텍스트를 clear하면 해당 변경 사항이 별도의 예외 없이 유실됩니다.
   */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      value =
          """
          UPDATE users
          SET email = :email,
              updated_at = NOW()
          WHERE id = :id
            AND NOT EXISTS (
                SELECT 1
                FROM users u2
                WHERE u2.email = :email
                  AND u2.id <> :id
            )
          """,
      nativeQuery = true)
  int updateEmailIfUnused(@Param("id") UUID id, @Param("email") String email);
}
