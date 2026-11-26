package works.momens.server.user.internal;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface UserRepository extends JpaRepository<User, UUID> {

  /** 같은 이메일의 사용자가 여럿인지 판별할 수 있도록 최대 두 건만 조회합니다. */
  List<User> findTop2ByEmail(String email);
}
