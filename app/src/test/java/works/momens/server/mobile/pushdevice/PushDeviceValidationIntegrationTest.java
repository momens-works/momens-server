package works.momens.server.mobile.pushdevice;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import works.momens.server.auth.AccessTokenTestFactory;
import works.momens.server.common.test.AbstractPostgresIntegrationTest;

@SpringBootTest
@AutoConfigureMockMvc
class PushDeviceValidationIntegrationTest extends AbstractPostgresIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private AccessTokenTestFactory accessTokens;

  @Test
  @DisplayName("공백 설치 ID는 등록과 삭제 모두 경로 변수의 검증 오류를 반환한다")
  void blankInstallationIdReturnsFieldDetails() throws Exception {
    URI path = URI.create("/api/me/push-devices/%20");
    String token = "Bearer " + accessTokens.issueAccessToken(UUID.randomUUID());

    mockMvc
        .perform(delete(path).header("Authorization", token).header("API-Version", "1"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("COMMON_VALIDATION_FAILED"))
        .andExpect(jsonPath("$.error.details.fields[0].field").value("firebaseInstallationId"))
        .andExpect(jsonPath("$.error.details.fields[0].reason").value("invalid value"));

    mockMvc
        .perform(
            put(path)
                .header("Authorization", token)
                .header("API-Version", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fcm_registration_token\":\"token\",\"platform\":\"android\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("COMMON_VALIDATION_FAILED"))
        .andExpect(jsonPath("$.error.details.fields[0].field").value("firebaseInstallationId"))
        .andExpect(jsonPath("$.error.details.fields[0].reason").value("invalid value"));
  }
}
