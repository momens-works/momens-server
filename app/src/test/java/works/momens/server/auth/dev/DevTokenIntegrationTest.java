package works.momens.server.auth.dev;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import works.momens.server.common.test.AbstractPostgresIntegrationTest;
import works.momens.server.user.UserProfile;
import works.momens.server.user.UserService;

/**
 * dev 전용 토큰 발급 엔드포인트(MOM-90) 통합테스트. test 프로필은 {@link
 * works.momens.server.auth.internal.config.DevOnly} 대상이라 엔드포인트가 등록됩니다.
 *
 * <p>공유 시크릿과 allowlist는 {@code application-test.yml}의 {@code momens.auth.dev-token}에서 옵니다. 발급된 토큰이
 * 운영과 같은 HS256이라 보호 API(`/api/me`)에서 실제로 인증되는지까지 확인합니다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DevTokenIntegrationTest extends AbstractPostgresIntegrationTest {

  private static final String SECRET_HEADER = "X-Dev-Token-Secret";
  private static final String SECRET = "test-only-momens-auth-dev-token-secret";
  private static final String API_VERSION_HEADER = "API-Version";
  private static final String API_VERSION = "1";

  @Autowired private MockMvc mockMvc;
  @Autowired private UserService userService;

  @Test
  void issuesTokenForDefaultAllowlistUserThatAuthenticatesProtectedApi() throws Exception {
    UUID firstAllowedId =
        userService.findOrCreate("owner@momens.works", "owner@momens.works", null).id();

    String response =
        mockMvc
            .perform(
                post("/api/dev/auth/token")
                    .header(SECRET_HEADER, SECRET)
                    .header(API_VERSION_HEADER, API_VERSION))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.token_type").value("Bearer"))
            .andExpect(jsonPath("$.access_token").isNotEmpty())
            .andReturn()
            .getResponse()
            .getContentAsString();

    String token = JsonPath.read(response, "$.access_token");
    mockMvc
        .perform(get("/api/me").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.user.id").value(firstAllowedId.toString()));
  }

  @Test
  void issuesTokenForRequestedAllowlistEmail() throws Exception {
    UUID memberId =
        userService.findOrCreate("member@momens.works", "member@momens.works", null).id();

    String response =
        mockMvc
            .perform(
                post("/api/dev/auth/token")
                    .header(SECRET_HEADER, SECRET)
                    .header(API_VERSION_HEADER, API_VERSION)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\":\"member@momens.works\"}"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    String token = JsonPath.read(response, "$.access_token");
    mockMvc
        .perform(get("/api/me").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.user.id").value(memberId.toString()));
  }

  @Test
  void rejectsWrongSecret() throws Exception {
    mockMvc
        .perform(
            post("/api/dev/auth/token")
                .header(SECRET_HEADER, "wrong-secret")
                .header(API_VERSION_HEADER, API_VERSION))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error.code").value("AUTH_DEV_TOKEN_SECRET_INVALID"));
  }

  @Test
  void rejectsMissingSecret() throws Exception {
    mockMvc
        .perform(post("/api/dev/auth/token").header(API_VERSION_HEADER, API_VERSION))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error.code").value("AUTH_DEV_TOKEN_SECRET_INVALID"));
  }

  @Test
  void rejectsEmailNotInAllowlist() throws Exception {
    mockMvc
        .perform(
            post("/api/dev/auth/token")
                .header(SECRET_HEADER, SECRET)
                .header(API_VERSION_HEADER, API_VERSION)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"stranger@momens.works\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("AUTH_DEV_TOKEN_EMAIL_NOT_ALLOWED"));
  }

  @Test
  void doesNotOverwriteExistingUserProfile() throws Exception {
    UUID userId =
        userService.findOrCreate("preserve@momens.works", "홍길동", "https://example.com/a.png").id();

    mockMvc
        .perform(
            post("/api/dev/auth/token")
                .header(SECRET_HEADER, SECRET)
                .header(API_VERSION_HEADER, API_VERSION)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"preserve@momens.works\"}"))
        .andExpect(status().isOk());

    // findOrCreate를 호출하면 기존 이름과 프로필 이미지를 갱신합니다.
    // dev 토큰 발급은 findByEmail로 기존 사용자를 먼저 조회하므로 저장된 프로필이 유지되어야 합니다.
    UserProfile after = userService.getProfile(userId);
    assertThat(after.name()).isEqualTo("홍길동");
    assertThat(after.avatarUrl()).isEqualTo("https://example.com/a.png");
  }
}
