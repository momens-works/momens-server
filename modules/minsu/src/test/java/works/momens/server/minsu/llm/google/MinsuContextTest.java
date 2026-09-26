package works.momens.server.minsu.llm.google;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Primary;
import works.momens.server.minsu.SignalTaskDraftGenerator;
import works.momens.server.minsu.SignalTaskDraftInput;
import works.momens.server.minsu.TaskDraft;
import works.momens.server.minsu.draft.ledger.TaskDraftGenerationEnroller;
import works.momens.server.minsu.llm.MinsuLlmProperties;
import works.momens.server.minsu.llm.ModelSelection;
import works.momens.server.project.task.TaskPriority;
import works.momens.server.project.task.TaskRole;

class MinsuContextTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
          .withUserConfiguration(TestApplication.class);

  @Test
  void startsWithValidEnabledConfigAndTurnsClientFactoryFailureIntoFallback() {
    contextRunner
        .withPropertyValues(
            "momens.minsu.task-draft.enabled=true",
            "momens.minsu.llm.provider=google",
            "momens.minsu.llm.model=gemini-3.5-flash-lite",
            "momens.minsu.llm.google.project=test-project",
            "momens.minsu.llm.google.location=global")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              TaskDraft draft =
                  context
                      .getBean(SignalTaskDraftGenerator.class)
                      .prepare(new SignalTaskDraftInput("시그널 제목", "risk", "설명", null, List.of()))
                      .draft();

              assertThat(draft)
                  .isEqualTo(new TaskDraft("시그널 제목", TaskRole.PM, TaskPriority.MEDIUM));
              assertThat(context.getBean(FailingGoogleClientFactory.class).calls).hasValue(1);
              assertThat(context.getBean(MinsuLlmProperties.class).timeout())
                  .isEqualTo(Duration.ofSeconds(8));
            });
  }

  @ParameterizedTest
  @MethodSource("invalidConfiguration")
  void startsWithInvalidProviderModelOrLocationAndDoesNotCreateClient(
      String property, String value) {
    contextRunner
        .withPropertyValues(
            "momens.minsu.task-draft.enabled=true",
            "momens.minsu.llm.provider=google",
            "momens.minsu.llm.model=gemini-3.5-flash-lite",
            "momens.minsu.llm.google.project=test-project",
            "momens.minsu.llm.google.location=global",
            property + "=" + value)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              context
                  .getBean(SignalTaskDraftGenerator.class)
                  .prepare(new SignalTaskDraftInput("시그널 제목", "risk", "설명", null, List.of()))
                  .draft();

              assertThat(context.getBean(FailingGoogleClientFactory.class).calls).hasValue(0);
              assertThat(
                      context
                          .getBean(MeterRegistry.class)
                          .get("momens.minsu.llm.config.valid")
                          .gauge()
                          .value())
                  .isZero();
            });
  }

  private static Stream<Arguments> invalidConfiguration() {
    return Stream.of(
        Arguments.of("momens.minsu.llm.provider", "other"),
        Arguments.of("momens.minsu.llm.model", "other-model"),
        Arguments.of("momens.minsu.llm.timeout", "0s"),
        Arguments.of("momens.minsu.llm.google.location", "asia-northeast3"));
  }

  // 슬라이스 테스트용 부트스트랩(MinsuModuleTestApplication)만 스캔에서 제외한다. 그 클래스가 잡히면
  // @EnableAutoConfiguration이 켜져 DataSource 없이 도는 이 컨텍스트에서 Flyway·JPA 자동 구성이
  // 실패한다(MOM-0817에서 minsu가 영속성을 갖게 된 뒤). 스캔 범위를 특정 nested module로 좁히는 대신 원인만
  // 빼는 이유는, 루트 패키지에 나중에 빈이 생겨도 이 테스트가 조용히 놓치지 않게 하기 위해서다.
  // 부트스트랩이 package-private이라 타입 대신 이름으로 지정한다.
  // 앱 통합 테스트가 명시적으로 import하는 root test fixture도 이 독립 컨텍스트에서는 제외한다.
  //
  // 원장 패키지도 같은 이유로 뺀다. 이 컨텍스트에는 DataSource가 없어 repository 빈이 만들어지지
  // 않는다. 이 테스트가 보는 것은 설정 축과 provider 배선이므로 적재기는 mock으로 대체한다.
  @Configuration(proxyBeanMethods = false)
  @ComponentScan(
      basePackages = "works.momens.server.minsu",
      excludeFilters =
          @ComponentScan.Filter(
              type = FilterType.REGEX,
              pattern =
                  "works\\.momens\\.server\\.minsu\\.MinsuModuleTestApplication"
                      + "|works\\.momens\\.server\\.minsu\\.MinsuLlmTestFixture.*"
                      + "|works\\.momens\\.server\\.minsu\\.draft\\.ledger\\..*"))
  static class TestApplication {

    @Bean
    TaskDraftGenerationEnroller taskDraftGenerationEnroller() {
      return mock(TaskDraftGenerationEnroller.class);
    }

    @Bean
    MeterRegistry meterRegistry() {
      return new SimpleMeterRegistry();
    }

    @Bean
    ObservationRegistry observationRegistry() {
      return ObservationRegistry.create();
    }

    @Bean
    FailingGoogleClientFactory failingGoogleClientFactory() {
      return new FailingGoogleClientFactory();
    }

    @Bean
    @Primary
    GoogleClientFactory testGoogleClientFactory(FailingGoogleClientFactory factory) {
      return factory;
    }
  }

  static final class FailingGoogleClientFactory implements GoogleClientFactory {

    private final AtomicInteger calls = new AtomicInteger();

    @Override
    public GoogleSdkClient create(ModelSelection selection) {
      calls.incrementAndGet();
      throw new IllegalStateException("ADC unavailable");
    }
  }
}
