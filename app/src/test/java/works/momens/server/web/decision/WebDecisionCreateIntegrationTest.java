package works.momens.server.web.decision;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import works.momens.server.auth.AccessTokenTestFactory;
import works.momens.server.common.api.BusinessException;
import works.momens.server.common.test.AbstractPostgresIntegrationTest;
import works.momens.server.project.core.ProjectReader;
import works.momens.server.project.decision.CreateDecisionCommand;
import works.momens.server.project.decision.DecisionWriter;

@DisplayName("Decision 생성 API 통합 테스트")
@SpringBootTest
@AutoConfigureMockMvc
class WebDecisionCreateIntegrationTest extends AbstractPostgresIntegrationTest {
  private static final String BODY =
      "{\"title\":\"PostgreSQL 사용\",\"context\":\"트랜잭션\",\"rationale\":\"운영 호환\"}";

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper mapper;
  @Autowired AccessTokenTestFactory tokens;
  @Autowired DataSource dataSource;
  @Autowired DecisionWriter writer;
  @Autowired ProjectReader projectReader;
  @Autowired PlatformTransactionManager transactionManager;

  @ParameterizedTest
  @ValueSource(strings = {"member", "admin", "owner"})
  @DisplayName("워크스페이스 멤버 이상은 Decision과 이벤트를 함께 저장하고 표준 생성 응답을 받는다")
  void createsWithStandardResponseAndAtomicEvent(String role) throws Exception {
    Fixture f = fixture(role);
    var response =
        create(f, BODY)
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.project_id").value(f.project().toString()))
            .andExpect(jsonPath("$.decision_maker").value(f.user().toString()))
            .andExpect(jsonPath("$.reversibility").value("reversible"))
            .andExpect(jsonPath("$.alternatives").doesNotExist())
            .andExpect(jsonPath("$.data").doesNotExist())
            .andReturn()
            .getResponse();
    var json = mapper.readTree(response.getContentAsString());
    UUID id = UUID.fromString(json.get("id").asText());
    Map<String, Object> row = jdbc.queryForMap("SELECT * FROM decisions WHERE id = ?", id);
    assertThat(row.get("title")).isEqualTo("PostgreSQL 사용");
    assertThat(row.get("context")).isEqualTo("트랜잭션");
    assertThat(row.get("rationale")).isEqualTo("운영 호환");
    assertThat(row.get("decision_maker")).isEqualTo(f.user());
    assertThat(json.get("created_at").asText()).isEqualTo(json.get("updated_at").asText());
    assertThat(((Timestamp) row.get("created_at")).toInstant())
        .isCloseTo(Instant.parse(json.get("created_at").asText()), within(1, ChronoUnit.MICROS));
    var events =
        jdbc.queryForList("SELECT * FROM outbox_events WHERE workspace_id = ?", f.workspace());
    assertThat(events).hasSize(1);
    assertThat(events.getFirst())
        .containsEntry("event_type", "decision.created")
        .containsEntry("issued_by", "api-server")
        .containsEntry("aggregate_type", "decision")
        .containsEntry("aggregate_id", id.toString())
        .containsEntry("idempotency_key", "decision.created:" + id);
    assertThat(events.getFirst().get("payload").toString()).isEqualTo("{}");
  }

  @ParameterizedTest
  @ValueSource(strings = {"null", "\"\"", "\"reversible\"", "\"irreversible\""})
  @DisplayName("가역성의 기본값과 허용 값을 적용하고 빈 대안은 응답에서 생략한다")
  void preservesOptionalDefaultsAndAllowedValues(String value) throws Exception {
    Fixture f = fixture("member");
    create(f, BODY.replace("}", ",\"reversibility\":" + value + ",\"alternatives\":\"\"}"))
        .andExpect(status().isCreated())
        .andExpect(
            jsonPath("$.reversibility")
                .value(value.contains("irreversible") ? "irreversible" : "reversible"))
        .andExpect(jsonPath("$.alternatives").doesNotExist());
  }

  @Test
  @DisplayName("입력 공백을 보존하고 요청 본문 대신 인증 사용자를 결정자로 저장한다")
  void preservesContentAndUsesAuthenticatedDecisionMaker() throws Exception {
    Fixture f = fixture("member");
    create(
            f,
            """
        {"title":" ","context":"  ","rationale":" ","alternatives":" MySQL ",
         "decision_maker":"00000000-0000-0000-0000-000000000000"}
        """)
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.title").value(" "))
        .andExpect(jsonPath("$.context").value("  "))
        .andExpect(jsonPath("$.alternatives").value(" MySQL "))
        .andExpect(jsonPath("$.decision_maker").value(f.user().toString()));
  }

  @ParameterizedTest
  @ValueSource(strings = {"\"unknown\"", "\"IRREVERSIBLE\"", "0", "\"0\""})
  @DisplayName("허용하지 않는 가역성은 표준 400 검증 오류로 거부하고 저장하지 않는다")
  void invalidReversibilityIsStandardValidationError(String value) throws Exception {
    Fixture f = fixture("member");
    create(f, BODY.replace("}", ",\"reversibility\":" + value + "}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("COMMON_VALIDATION_FAILED"))
        .andExpect(jsonPath("$.error.details.fields[0].field").value("reversibility"));
    assertNoWrites(f);
  }

  @ParameterizedTest
  @ValueSource(strings = {"title", "context", "rationale"})
  @DisplayName("필수 필드의 누락·null·빈 문자열은 표준 400 검증 오류로 거부한다")
  void requiredFieldsRejectMissingNullAndEmpty(String field) throws Exception {
    Fixture f = fixture("member");
    for (String invalid : new String[] {null, "null", "\"\""}) {
      var body = mapper.readTree(BODY).deepCopy();
      var object = (ObjectNode) body;
      if (invalid == null) {
        object.remove(field);
      } else {
        object.set(field, mapper.readTree(invalid));
      }
      create(f, mapper.writeValueAsString(object))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.error.code").value("COMMON_VALIDATION_FAILED"))
          .andExpect(jsonPath("$.error.details.fields[0].field").value(field));
    }
    assertNoWrites(f);
  }

  @Test
  @DisplayName("미인증·비멤버·없는 프로젝트·삭제된 프로젝트·잘못된 식별자를 거부한다")
  void rejectsUnauthenticatedNonMemberAndDeletedProject() throws Exception {
    Fixture f = fixture("member");
    mvc.perform(
            post("/api/projects/{id}/decisions", f.project())
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error.code").value("AUTH_UNAUTHORIZED"));
    jdbc.update("DELETE FROM workspace_members WHERE workspace_id = ?", f.workspace());
    create(f, BODY)
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("AUTH_FORBIDDEN"))
        .andExpect(jsonPath("$.error.details.required_role").value("member"));
    jdbc.update("UPDATE projects SET deleted_at = now() WHERE id = ?", f.project());
    create(f, BODY)
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("PROJECT_NOT_FOUND"));
    mvc.perform(request(f.user(), UUID.randomUUID().toString(), BODY))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("PROJECT_NOT_FOUND"));
    mvc.perform(request(f.user(), "bad-id", BODY))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("COMMON_BAD_REQUEST"));
    assertNoWrites(f);
  }

  @Test
  @DisplayName("writer는 다른 워크스페이스에 속한 프로젝트의 Decision 생성을 거부한다")
  void writerRejectsProjectFromAnotherWorkspace() {
    Fixture f = fixture("member");
    assertThatThrownBy(
            () ->
                writer.create(
                    new CreateDecisionCommand(
                        f.project(),
                        UUID.randomUUID(),
                        f.user(),
                        "title",
                        "context",
                        null,
                        "rationale",
                        null)))
        .isInstanceOf(BusinessException.class);
    assertNoWrites(f);
  }

  @Test
  @DisplayName("outbox 발행 실패 시 Decision도 롤백하고 내부 DB 오류를 응답에 노출하지 않는다")
  void outboxFailureRollsBackDecisionAndDoesNotExposeDatabaseError() throws Exception {
    Fixture f = fixture("member");
    jdbc.execute(
        """
        CREATE FUNCTION fail_decision_outbox() RETURNS trigger LANGUAGE plpgsql AS $$
        BEGIN RAISE EXCEPTION 'private decision database failure'; END; $$
        """);
    jdbc.execute(
        """
        CREATE TRIGGER fail_decision_outbox BEFORE INSERT ON outbox_events
        FOR EACH ROW WHEN (NEW.event_type = 'decision.created')
        EXECUTE FUNCTION fail_decision_outbox()
        """);
    try {
      var response =
          create(f, BODY)
              .andExpect(status().isInternalServerError())
              .andExpect(jsonPath("$.error.code").value("COMMON_INTERNAL_SERVER_ERROR"))
              .andReturn()
              .getResponse()
              .getContentAsString();
      assertThat(response).doesNotContain("private decision database failure", "SQL", "constraint");
      assertNoWrites(f);
    } finally {
      jdbc.execute("DROP TRIGGER fail_decision_outbox ON outbox_events");
      jdbc.execute("DROP FUNCTION fail_decision_outbox()");
    }
  }

  @Test
  @DisplayName("Decision 저장에 실패하면 outbox 이벤트를 발행하지 않는다")
  void failedDecisionInsertDoesNotPublishEvent() {
    Fixture f = fixture("member");
    assertThatThrownBy(
            () ->
                writer.create(
                    new CreateDecisionCommand(
                        f.project(),
                        f.workspace(),
                        UUID.randomUUID(),
                        "title",
                        "context",
                        null,
                        "rationale",
                        null)))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertNoWrites(f);
  }

  @Test
  @DisplayName("프로젝트 삭제가 먼저 커밋되면 대기 중인 Decision 생성은 404로 거부된다")
  void deletionThatCommitsBeforeCreationLockRejectsCreation() throws Exception {
    Fixture f = fixture("member");
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      JdbcTemplate deleting = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
      int pid = deleting.queryForObject("SELECT pg_backend_pid()", Integer.class);
      deleting.update("UPDATE projects SET deleted_at = now() WHERE id = ?", f.project());
      try (var executor = Executors.newSingleThreadExecutor()) {
        var creating = executor.submit(() -> create(f, BODY).andReturn().getResponse());
        try {
          awaitBlockedBy(pid);
        } finally {
          connection.commit();
        }
        assertThat(creating.get(15, TimeUnit.SECONDS).getStatus()).isEqualTo(404);
      }
    }
    assertNoWrites(f);
  }

  @Test
  @DisplayName("Decision 생성이 먼저 잠금을 얻으면 커밋까지 프로젝트 삭제가 대기한다")
  void creationLockBlocksProjectDeletionUntilCommit() throws Exception {
    Fixture f = fixture("member");
    var transaction = new TransactionTemplate(transactionManager);
    try (var executor = Executors.newSingleThreadExecutor()) {
      var deleting =
          transaction.execute(
              status -> {
                projectReader.lockWorkspaceIdOf(f.project()).orElseThrow();
                int pid = jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
                var future =
                    executor.submit(
                        () ->
                            jdbc.update(
                                "UPDATE projects SET deleted_at = now() WHERE id = ?",
                                f.project()));
                try {
                  awaitBlockedBy(pid);
                  writer.create(
                      new CreateDecisionCommand(
                          f.project(),
                          f.workspace(),
                          f.user(),
                          "title",
                          "context",
                          null,
                          "rationale",
                          null));
                } catch (Exception e) {
                  throw new IllegalStateException(e);
                }
                return future;
              });
      assertThat(deleting.get(15, TimeUnit.SECONDS)).isEqualTo(1);
    }
    assertThat(count("decisions", "project_id", f.project())).isEqualTo(1);
    assertThat(count("outbox_events", "workspace_id", f.workspace())).isEqualTo(1);
  }

  @Test
  @DisplayName("기본값과 선택 필드의 실제 요청·응답이 OpenAPI 명세와 일치한다")
  void openApiAcceptsActualDefaultAndOptionalFieldContracts() throws Exception {
    String spec =
        mvc.perform(get("/api/v3/api-docs"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var validator = OpenApiInteractionValidator.createForInlineApiSpecification(spec).build();
    Fixture f = fixture("member");
    for (String optional :
        new String[] {
          "", ",\"reversibility\":\"\",\"alternatives\":null",
          ",\"reversibility\":null",
              ",\"reversibility\":\"irreversible\",\"alternatives\":\"MySQL\""
        }) {
      create(f, BODY.replace("}", optional + "}"))
          .andExpect(status().isCreated())
          .andExpect(openApi().isValid(validator));
    }
  }

  @Test
  @DisplayName("공유 잠금은 같은 프로젝트의 다른 Decision 생성을 차단하지 않는다")
  void creationLocksAllowAnotherDecisionInTheSameProject() throws Exception {
    Fixture f = fixture("member");
    var transaction = new TransactionTemplate(transactionManager);
    try (var executor = Executors.newSingleThreadExecutor()) {
      transaction.executeWithoutResult(
          status -> {
            projectReader.lockWorkspaceIdOf(f.project()).orElseThrow();
            var creating =
                executor.submit(
                    () ->
                        writer.create(
                            new CreateDecisionCommand(
                                f.project(),
                                f.workspace(),
                                f.user(),
                                "title",
                                "context",
                                null,
                                "rationale",
                                null)));
            try {
              assertThat(creating.get(5, TimeUnit.SECONDS).projectId()).isEqualTo(f.project());
            } catch (Exception e) {
              throw new IllegalStateException(e);
            }
          });
    }
    assertThat(count("decisions", "project_id", f.project())).isEqualTo(1);
  }

  private void awaitBlockedBy(int pid) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    do {
      if (jdbc.queryForObject(
              "SELECT count(*) FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid))",
              Integer.class,
              pid)
          > 0) {
        return;
      }
      Thread.sleep(20);
    } while (System.nanoTime() < deadline);
    throw new AssertionError("competing transaction did not reach the project row lock");
  }

  private ResultActions create(Fixture f, String body) throws Exception {
    return mvc.perform(request(f.user(), f.project().toString(), body));
  }

  private MockHttpServletRequestBuilder request(UUID user, String project, String body) {
    return post("/api/projects/{id}/decisions", project)
        .header("Authorization", "Bearer " + tokens.issueAccessToken(user))
        .header("API-Version", "1")
        .contentType(MediaType.APPLICATION_JSON)
        .content(body);
  }

  private void assertNoWrites(Fixture f) {
    assertThat(count("decisions", "project_id", f.project())).isZero();
    assertThat(count("outbox_events", "workspace_id", f.workspace())).isZero();
  }

  private int count(String table, String column, UUID value) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM " + table + " WHERE " + column + " = ?", Integer.class, value);
  }

  private Fixture fixture(String role) {
    UUID user = UUID.randomUUID();
    UUID workspace = UUID.randomUUID();
    UUID project = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO users (id, email, name) VALUES (?, ?, ?)",
        user,
        user + "@example.com",
        "user");
    jdbc.update(
        "INSERT INTO workspaces (id, name, slug) VALUES (?, ?, ?)",
        workspace,
        "workspace",
        workspace.toString());
    jdbc.update(
        "INSERT INTO workspace_members (workspace_id, user_id, role) VALUES (?, ?, ?)",
        workspace,
        user,
        role);
    jdbc.update(
        "INSERT INTO projects (id, workspace_id, name, owner_id) VALUES (?, ?, ?, ?)",
        project,
        workspace,
        "project",
        user);
    return new Fixture(user, workspace, project);
  }

  private record Fixture(UUID user, UUID workspace, UUID project) {}
}
