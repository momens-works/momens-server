package works.momens.server.user.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import works.momens.server.common.persistence.JpaAuditingConfig;
import works.momens.server.common.test.AbstractPostgresIntegrationTest;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaAuditingConfig.class)
class UserRepositoryIntegrationTest extends AbstractPostgresIntegrationTest {

  @Autowired private UserRepository userRepository;
  @Autowired private TestEntityManager entityManager;

  @Test
  void savesUserWithGeneratedIdAndAuditFields() {
    User saved = userRepository.save(User.builder().email("a@momens.works").name("홍길동").build());

    assertThat(saved.getId()).isNotNull();
    assertThat(saved.getId().version()).as("UUID v4 PK").isEqualTo(4);
    assertThat(saved.getCreatedAt()).isNotNull();
    assertThat(saved.getUpdatedAt()).isNotNull();
  }

  @Test
  void readsBackPersistedUser() {
    User saved = userRepository.save(User.builder().email("b@momens.works").name("이름").build());
    entityManager.flush();
    entityManager.clear();

    User found = userRepository.findById(saved.getId()).orElseThrow();

    assertThat(found.getEmail()).isEqualTo("b@momens.works");
    assertThat(found.getName()).isEqualTo("이름");
    assertThat(found.getCreatedAt()).isNotNull();
  }
}
