package works.momens.server.web.memory;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.config.annotation.ApiVersionConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import works.momens.server.web.memory.dto.response.ConfirmedMemoryResponse;

/**
 * 후보 리뷰·메모리 해결 endpoint의 웹 계약을 검증합니다.
 *
 * <p>요청 필드 이름과 성공 응답 형식이 레거시와 같은지가 핵심입니다. 두 서버가 같은 웹 FE를 상대하므로 키 하나만 달라도 컷오버 시점에 그 기능이 죽습니다.
 */
@WebMvcTest(MemoryWriteController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(MemoryWriteControllerTest.ApiVersioningTestConfig.class)
@DisplayName("MemoryWriteController 테스트")
class MemoryWriteControllerTest {

  @Autowired private MockMvc mockMvc;
  @MockitoBean private MemoryWriteService memoryWriteService;

  private static final UUID USER_ID = UUID.fromString("5d2f7f3a-5db1-4f2c-8b9e-13607dd1f5e8");
  private static final UUID CANDIDATE_ID = UUID.fromString("30d9e9fe-f43b-4097-a88e-dc19f0a5b025");
  private static final UUID MEMORY_ID = UUID.fromString("9d0a1a51-7f5f-4c6a-9b7a-1c0b4d5e6f70");
  private static final UUID TARGET_ID = UUID.fromString("2f0d3a7c-6a44-4a2f-9b04-5f2a9c7f8d31");
  private final Principal principal = USER_ID::toString;

  @Test
  @DisplayName("확정은 201과 확정 메모리를 레거시 필드 이름으로 응답한다")
  void confirmReturnsCreatedMemory() throws Exception {
    when(memoryWriteService.confirm(eq(CANDIDATE_ID), eq(USER_ID))).thenReturn(fullMemory());

    mockMvc
        .perform(
            post("/api/memory-candidates/{candidateId}/confirm", CANDIDATE_ID)
                .principal(principal)
                .header("API-Version", "1"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(MEMORY_ID.toString()))
        .andExpect(jsonPath("$.label").value("MEM-0001"))
        .andExpect(jsonPath("$.memory_type").value("DECISION"))
        .andExpect(jsonPath("$.source_ref_ids.length()").value(1))
        .andExpect(jsonPath("$.confirmed_at").value("2026-08-21T09:00:00Z"));
  }

  @Test
  @DisplayName("값이 없는 필드는 키 자체를 내보내지 않는다")
  void confirmOmitsEmptyFieldsLikeLegacy() throws Exception {
    when(memoryWriteService.confirm(eq(CANDIDATE_ID), eq(USER_ID))).thenReturn(sparseMemory());

    mockMvc
        .perform(
            post("/api/memory-candidates/{candidateId}/confirm", CANDIDATE_ID)
                .principal(principal)
                .header("API-Version", "1"))
        .andExpect(status().isCreated())
        // 레거시 domain.ConfirmedMemory는 이 필드들에 omitempty가 걸려 있습니다.
        .andExpect(jsonPath("$.summary").doesNotExist())
        .andExpect(jsonPath("$.body").doesNotExist())
        .andExpect(jsonPath("$.valid_from").doesNotExist())
        .andExpect(jsonPath("$.metadata").doesNotExist())
        // 빈 배열도 키를 내보내지 않습니다(웹 snapshot 계약 4.2).
        .andExpect(jsonPath("$.source_ref_ids").doesNotExist())
        .andExpect(jsonPath("$.related_entity_ids").doesNotExist())
        // 값이 있는 필드는 그대로 나갑니다.
        .andExpect(jsonPath("$.title").value("제목만 있는 메모리"))
        .andExpect(jsonPath("$.status").value("ACTIVE"));
  }

  @Test
  @DisplayName("수정 후 확정은 편집 필드를 서비스에 전달한다")
  void editAndConfirmPassesEditsToService() throws Exception {
    when(memoryWriteService.editAndConfirm(
            eq(CANDIDATE_ID), eq(USER_ID), eq("고친 제목"), eq(null), eq("고친 본문")))
        .thenReturn(fullMemory());

    mockMvc
        .perform(
            post("/api/memory-candidates/{candidateId}/edit-and-confirm", CANDIDATE_ID)
                .principal(principal)
                .header("API-Version", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"고친 제목\",\"body\":\"고친 본문\"}"))
        .andExpect(status().isCreated());

    verify(memoryWriteService).editAndConfirm(CANDIDATE_ID, USER_ID, "고친 제목", null, "고친 본문");
  }

  @Test
  @DisplayName("병합은 레거시 merge_target_memory_id 키를 받는다")
  void mergeBindsLegacyRequestField() throws Exception {
    mockMvc
        .perform(
            post("/api/memory-candidates/{candidateId}/merge", CANDIDATE_ID)
                .principal(principal)
                .header("API-Version", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"merge_target_memory_id\":\"" + TARGET_ID + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.message").value("merged"));

    verify(memoryWriteService).merge(CANDIDATE_ID, USER_ID, TARGET_ID);
  }

  @Test
  @DisplayName("거절은 본문 없이도 받고 rejected 메시지로 응답한다")
  void rejectAcceptsMissingBody() throws Exception {
    mockMvc
        .perform(
            post("/api/memory-candidates/{candidateId}/reject", CANDIDATE_ID)
                .principal(principal)
                .header("API-Version", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.message").value("rejected"));

    verify(memoryWriteService).reject(CANDIDATE_ID, USER_ID, null);
  }

  @Test
  @DisplayName("만료는 expired 메시지로 응답한다")
  void expireReturnsMessage() throws Exception {
    mockMvc
        .perform(
            post("/api/memory-candidates/{candidateId}/expire", CANDIDATE_ID)
                .principal(principal)
                .header("API-Version", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.message").value("expired"));

    verify(memoryWriteService).expire(CANDIDATE_ID, USER_ID);
  }

  @Test
  @DisplayName("해결은 resolving_memory_id를 받아 resolved 메시지로 응답한다")
  void resolveBindsLegacyRequestField() throws Exception {
    mockMvc
        .perform(
            post("/api/memories/{memoryId}/resolve", MEMORY_ID)
                .principal(principal)
                .header("API-Version", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"resolving_memory_id\":\"" + TARGET_ID + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.message").value("resolved"));

    verify(memoryWriteService).resolve(MEMORY_ID, TARGET_ID, USER_ID);
  }

  private ConfirmedMemoryResponse fullMemory() {
    return new ConfirmedMemoryResponse(
        MEMORY_ID,
        UUID.fromString("7c2f0f6a-9c07-4b3f-8a3d-2f6b0f1c9a44"),
        "MEM-0001",
        "DECISION",
        "결제 재시도는 3회로 고정한다",
        "요약",
        "본문",
        "ACTIVE",
        List.of(TARGET_ID),
        List.of(CANDIDATE_ID),
        CANDIDATE_ID,
        USER_ID,
        Instant.parse("2026-08-21T09:00:00Z"),
        null,
        null,
        null,
        null,
        null,
        Map.of("origin", "slack"),
        Instant.parse("2026-08-21T09:00:00Z"),
        Instant.parse("2026-08-21T09:00:00Z"));
  }

  private ConfirmedMemoryResponse sparseMemory() {
    return new ConfirmedMemoryResponse(
        MEMORY_ID,
        UUID.fromString("7c2f0f6a-9c07-4b3f-8a3d-2f6b0f1c9a44"),
        null,
        "DECISION",
        "제목만 있는 메모리",
        null,
        null,
        "ACTIVE",
        List.of(),
        List.of(),
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        Map.of(),
        Instant.parse("2026-08-21T09:00:00Z"),
        Instant.parse("2026-08-21T09:00:00Z"));
  }

  @TestConfiguration
  static class ApiVersioningTestConfig implements WebMvcConfigurer {
    @Override
    public void configureApiVersioning(ApiVersionConfigurer configurer) {
      configurer.useRequestHeader("API-Version").addSupportedVersions("1").setDefaultVersion("1");
    }
  }
}
