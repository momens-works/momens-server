package works.momens.server;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import works.momens.server.common.test.AbstractPostgresIntegrationTest;
import works.momens.server.minsu.SignalTaskDraftGenerator;

/**
 * 애플리케이션 컨텍스트 로드 테스트.
 *
 * <p>실제 PostgreSQL(pgvector) 컨테이너 위에서 datasource, JPA, Flyway(모듈별 마이그레이션 병합) 자동 구성을 포함한 전체 컨텍스트가 정상
 * 로드되는지 검증합니다. 컨테이너 설정은 {@link AbstractPostgresIntegrationTest}가 제공합니다.
 */
@SpringBootTest
class MomensServerApplicationTests extends AbstractPostgresIntegrationTest {

  private final SignalTaskDraftGenerator signalTaskDraftGenerator;
  private final Environment environment;

  @Autowired
  MomensServerApplicationTests(
      SignalTaskDraftGenerator signalTaskDraftGenerator, Environment environment) {
    this.signalTaskDraftGenerator = signalTaskDraftGenerator;
    this.environment = environment;
  }

  @Test
  void contextLoadsWithMinsuDisabledByDefault() {
    assertThat(signalTaskDraftGenerator).isNotNull();
    assertThat(environment.getProperty("momens.minsu.task-draft.enabled", Boolean.class)).isFalse();
    assertThat(environment.getProperty("momens.minsu.task-draft.async.enroll", Boolean.class))
        .isFalse();
    assertThat(environment.getProperty("momens.minsu.task-draft.async.drain", Boolean.class))
        .isFalse();
  }
}
