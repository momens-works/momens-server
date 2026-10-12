package works.momens.server.notification.consume;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import works.momens.server.auth.AccessTokenTestFactory;
import works.momens.server.common.test.AbstractPostgresIntegrationTest;
import works.momens.server.notification.dispatch.PushDispatcher;
import works.momens.server.notification.fcm.FcmClient;
import works.momens.server.notification.fcm.PushMessage;
import works.momens.server.user.UserProfile;
import works.momens.server.user.UserService;

/**
 * dev Signal 생성 API부터 FCM 발송 직전(adapter 경계)까지의 수직 흐름 통합 테스트.
 *
 * <p>실토큰과 실제 PostgreSQL로 기기 등록 → dev Signal 생성(원자 저장) → consumer materialization → 발송 패스까지 끝까지
 * 확인합니다. 외부 FCM만 기록형 fake로 대체하고, push 문구·data payload 계약과 중복 소비 방지를 검증합니다. 폴링 스케줄러는 test 프로필에서 꺼져
 * 있어 consumer·발송기를 직접 호출합니다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SignalPushEndToEndIntegrationTest extends AbstractPostgresIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private AccessTokenTestFactory accessTokens;
  @Autowired private UserService userService;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private SignalCreatedDeliveryMaterializer materializer;
  @Autowired private NotificationConsumerOffsetRepository offsetRepository;
  @Autowired private PushDispatcher pushDispatcher;
  @Autowired private RecordingFcmClient recordingFcmClient;

  private Long foreignOutboxEventId;

  /**
   * 적대적으로 만든 회귀 가드 event를 안전 지연 밖으로 밀어 뒤 클래스에 물려주지 않는다.
   *
   * <p>이 클래스는 전진된 offset 행과 본문 signal.created event도 남기지만, 둘 다 안전 지연 밖이거나 소비 완료라 뒤를 막지 않는다. 소비 직전
   * {@code NOW()}로 되살리는 가드만이 뒤 클래스의 prefix를 자를 수 있는 행이다.
   */
  @AfterEach
  void backDateGuardEvent() {
    if (foreignOutboxEventId != null) {
      jdbcTemplate.update(
          "UPDATE outbox_events SET created_at = created_at - interval '5 seconds' WHERE id = ?",
          foreignOutboxEventId);
    }
  }

  @Test
  @DisplayName("dev Signal 생성이 workspace 전체 구성원의 활성 기기로 정확히 한 번의 push를 만든다")
  void devSignalCreationDeliversPushToWorkspaceMembers() throws Exception {
    // 앞선 테스트 클래스가 안전 지연(2초) 안에 남긴 outbox event를 재현한다. 이 event가 안전 prefix를 막으므로
    // materialize()로 watermark를 시드하면 끝까지 전진하지 못한다.
    foreignOutboxEventId = insertFreshForeignOutboxEvent();

    // E2E fixture 경계: 가드까지를 소비 완료로 표시해 이 테스트가 이후 만드는 event만 소비 대상으로 남긴다.
    // 운영 최초 시드(안전 지연을 지난 연속 prefix 끝까지만 전진)와 달리 fresh event를 건너뛰고 강제 전진하므로
    // 운영 동작과 같지 않다. 운영 시드 의미는 SignalCreatedDeliveryMaterializerIntegrationTest가 검증한다.
    forceSeedWatermarkPast(foreignOutboxEventId);

    UserProfile owner = userService.findOrCreate("push-e2e-owner@momens.works", "홍길동", null);
    UserProfile memberWithDevice =
        userService.findOrCreate("push-e2e-member@momens.works", "김철수", null);
    UserProfile memberWithoutDevice =
        userService.findOrCreate("push-e2e-nodevice@momens.works", "이영희", null);
    UUID workspace = insertWorkspace("push-e2e");
    addMember(workspace, owner.id(), "owner");
    addMember(workspace, memberWithDevice.id(), "member");
    addMember(workspace, memberWithoutDevice.id(), "member");
    UUID project = insertProject(workspace, owner.id(), "Q2 Activation Readiness");

    registerDevice(owner.id(), "fid-owner", "token-owner");
    registerDevice(memberWithDevice.id(), "fid-member", "token-member");

    MvcResult created =
        mockMvc
            .perform(
                post("/api/dev/projects/{projectId}/signals", project)
                    .header("Authorization", "Bearer " + accessTokens.issueAccessToken(owner.id()))
                    .header("API-Version", "1")
                    .contentType("application/json")
                    .content(
                        """
                        {
                          "type": "risk",
                          "title": "결제 정책 결정 3일째 보류",
                          "description": "결제 정책 결정이 3일 동안 보류된 상태입니다.",
                          "occurred_at": "2026-07-16T10:30:00+09:00",
                          "evidence": [
                            {
                              "source_type": "slack",
                              "source_title": "결제 정책 논의",
                              "source_snippet": "정책 결정이 아직 보류 상태입니다.",
                              "source_text": "원문 전체 내용",
                              "source_url": "https://example.com/source/1",
                              "occurred_at": "2026-07-16T09:20:00+09:00",
                              "details": {"target": "결제 정책", "change": "결정 3일째 보류", "impact": "일정 지연 가능"}
                            }
                          ]
                        }
                        """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").isNotEmpty())
            .andReturn();
    UUID signalId =
        UUID.fromString(
            new ObjectMapper()
                .readTree(created.getResponse().getContentAsString())
                .get("id")
                .asText());

    // Signal·evidence·source_refs·outbox가 한 트랜잭션으로 저장됐다.
    assertThat(countBy("signals", "id", signalId)).isEqualTo(1);
    assertThat(countBy("signal_evidence", "signal_id", signalId)).isEqualTo(1);
    Long outboxEventId =
        jdbcTemplate.queryForObject(
            "SELECT id FROM outbox_events WHERE idempotency_key = ?",
            Long.class,
            "signal.created:" + signalId);
    assertThat(outboxEventId).isNotNull();
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM source_refs WHERE workspace_id = ? AND source_type = 'slack'",
                Long.class,
                workspace))
        .isEqualTo(1);

    // 안전 지연(2초)을 기다리는 대신 event 생성 시각을 지연 이전으로 되돌린다. 본문이 만든 event 전부가 대상이다.
    // 이 흐름이 나중에 event를 하나 더 발행해도 그 event가 fresh로 남아 prefix를 자르지 않는다.
    jdbcTemplate.update(
        "UPDATE outbox_events SET created_at = created_at - interval '5 seconds' WHERE id > ?",
        foreignOutboxEventId);

    // 회귀 가드를 소비 시점에도 살려 둔다. 여기까지 오는 데 걸린 시간과 무관하게, 시드가 안전 지연에 다시 의존하면
    // 이 event가 prefix를 막아 테스트가 실패한다.
    jdbcTemplate.update(
        "UPDATE outbox_events SET created_at = NOW() WHERE id = ?", foreignOutboxEventId);

    materializer.materialize();
    pushDispatcher.runSendPass();

    // workspace 구성원 중 활성 기기를 등록한 2명에게만, 계약된 문구·data payload로 발송된다.
    assertThat(recordingFcmClient.sentTokens())
        .containsExactlyInAnyOrder("token-owner", "token-member");
    PushMessage message = recordingFcmClient.lastMessage();
    assertThat(message.title()).isEqualTo("Q2 Activation Readiness에 새 시그널이 발견되었습니다.");
    assertThat(message.body()).isEqualTo("결제 정책 결정 3일째 보류");
    assertThat(message.data())
        .containsEntry("notification_type", "signal_created")
        .containsEntry("destination", "signal_detail")
        .containsEntry("signal_id", signalId.toString())
        .containsEntry("project_id", project.toString())
        .containsEntry("workspace_id", workspace.toString());
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM push_deliveries WHERE outbox_event_id = ? AND status = 'sent'",
                Long.class,
                outboxEventId))
        .isEqualTo(2);

    // 같은 event를 다시 소비해도 중복 push가 생기지 않는다.
    recordingFcmClient.reset();
    NotificationConsumerOffset offset =
        offsetRepository.findById(SignalCreatedDeliveryMaterializer.CONSUMER_NAME).orElseThrow();
    offset.advanceTo(outboxEventId - 1);
    offsetRepository.saveAndFlush(offset);
    materializer.materialize();
    pushDispatcher.runSendPass();
    assertThat(recordingFcmClient.sentTokens()).isEmpty();
  }

  private void registerDevice(UUID userId, String fid, String token) throws Exception {
    mockMvc
        .perform(
            put("/api/me/push-devices/{fid}", fid)
                .header("Authorization", "Bearer " + accessTokens.issueAccessToken(userId))
                .header("API-Version", "1")
                .contentType("application/json")
                .content(
                    """
                    {"fcm_registration_token": "%s", "platform": "android"}
                    """
                        .formatted(token)))
        .andExpect(status().isNoContent());
  }

  private void forceSeedWatermarkPast(long outboxEventId) {
    jdbcTemplate.update(
        """
        INSERT INTO notification_consumer_offsets (consumer_name, last_outbox_id, created_at, updated_at)
        VALUES (?, ?, NOW(), NOW())
        ON CONFLICT (consumer_name)
        DO UPDATE SET last_outbox_id = EXCLUDED.last_outbox_id, updated_at = NOW()
        """,
        SignalCreatedDeliveryMaterializer.CONSUMER_NAME,
        outboxEventId);
  }

  /**
   * 안전 prefix를 자를 fresh event 하나를 심는다.
   *
   * <p>{@code event_type}은 이 테스트 전용 값이다. 안전 prefix 쿼리는 {@code id}·{@code created_at}만 보므로 가드 역할은
   * 타입과 무관한 반면, 실제 event_type을 빌려 쓰면 같은 DB를 공유하는 다른 테스트와 결합된다({@code
   * OutboxAtomicityIntegrationTest}는 {@code signal.converted_to_task} INSERT를 실패시키는 trigger를 건다).
   */
  private long insertFreshForeignOutboxEvent() {
    Long id =
        jdbcTemplate.queryForObject(
            """
            INSERT INTO outbox_events
              (issued_by, workspace_id, aggregate_type, aggregate_id, event_type, payload, idempotency_key)
            VALUES ('api-server', ?, 'signal', ?, 'push-e2e.foreign', '{}'::jsonb, ?)
            RETURNING id
            """,
            Long.class,
            UUID.randomUUID(),
            UUID.randomUUID().toString(),
            "push-e2e-foreign:" + UUID.randomUUID());
    return requireNonNull(id);
  }

  private long countBy(String table, String column, UUID id) {
    Long count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM " + table + " WHERE " + column + " = ?", Long.class, id);
    return count == null ? 0 : count;
  }

  private UUID insertWorkspace(String slug) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO workspaces (id, name, slug) VALUES (?, ?, ?)", id, "모멘스", slug);
    return id;
  }

  private void addMember(UUID workspaceId, UUID userId, String role) {
    jdbcTemplate.update(
        "INSERT INTO workspace_members (workspace_id, user_id, role) VALUES (?, ?, ?)",
        workspaceId,
        userId,
        role);
  }

  private UUID insertProject(UUID workspaceId, UUID ownerId, String name) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO projects (id, workspace_id, name, owner_id) VALUES (?, ?, ?, ?)",
        id,
        workspaceId,
        name,
        ownerId);
    return id;
  }

  /** 외부 FCM 경계만 기록형 fake로 대체한다. 전 기기 성공으로 응답한다. */
  @TestConfiguration
  static class RecordingFcmClientConfig {
    @Bean
    @Primary
    RecordingFcmClient recordingFcmClient() {
      return new RecordingFcmClient();
    }
  }

  static class RecordingFcmClient implements FcmClient {

    private final List<String> sentTokens = Collections.synchronizedList(new ArrayList<>());
    private volatile PushMessage lastMessage;

    @Override
    public List<FcmSendResult> send(List<String> tokens, PushMessage message) {
      sentTokens.addAll(tokens);
      lastMessage = message;
      return Collections.nCopies(tokens.size(), FcmSendResult.SENT);
    }

    List<String> sentTokens() {
      return List.copyOf(sentTokens);
    }

    PushMessage lastMessage() {
      return lastMessage;
    }

    void reset() {
      sentTokens.clear();
      lastMessage = null;
    }
  }
}
