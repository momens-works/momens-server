package works.momens.server.observability;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import works.momens.server.common.test.AbstractPostgresIntegrationTest;

/**
 * 지표 엔드포인트의 노출과 접근 제어(MOM-0833).
 *
 * <p>운영 노출은 local 프로필에만 있고(`application-local.yml`), test 프로필이 같은 값을 갖도록 `application-test.yml`에 둬서
 * <b>노출됐을 때의 동작</b>을 고정한다.
 *
 * <p><b>{@code @SpringBootTest(properties = ...)}로 주입하지 않는다.</b> 그 값이 컨텍스트 캐시 키에 들어가 컨텍스트가 하나 더
 * 생기고, 컨텍스트마다 커넥션 풀이 하나씩 붙는다. Testcontainers 컨테이너는 모든 컨텍스트가 공유하므로 그만큼 Postgres 연결 상한을 함께 쓴다. 지금
 * 컨텍스트가 10개이고 기본 풀 크기가 10이라 여유가 거의 없다. 실제로 이 테스트를 {@code properties}로 만들었을 때 11번째 컨텍스트가 생겨 무관한 테스트가
 * {@code too many clients already}로 깨졌다. 새 컨텍스트를 만드는 조합(프로퍼티·모의 빈·프로필)을 추가할 때 같은 문제가 다시 난다.
 *
 * <p>확인하는 것은 둘이다. 엔드포인트를 열어도 {@code SecurityConfig}의 {@code PUBLIC_PATHS}에는 {@code
 * /actuator/health/**}만 있으므로 지표는 <b>보호 체인에 걸려 인증을 요구한다.</b> 그리고 인증하면 실제로 지표를 돌려준다. 앞의 것이 깨지면 지표가
 * 공개되고, 뒤의 것이 깨지면 열어 둔 의미가 없다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MetricsEndpointIntegrationTest extends AbstractPostgresIntegrationTest {

  private static final String SECRET_HEADER = "X-Dev-Token-Secret";
  private static final String SECRET = "test-only-momens-auth-dev-token-secret";
  private static final String API_VERSION_HEADER = "API-Version";
  private static final String API_VERSION = "1";

  @Autowired private MockMvc mockMvc;

  @Test
  void requiresAuthenticationEvenWhenExposed() throws Exception {
    mockMvc.perform(get("/actuator/metrics")).andExpect(status().isUnauthorized());
  }

  @Test
  void servesMetricsToAuthenticatedCaller() throws Exception {
    mockMvc
        .perform(get("/actuator/metrics").header("Authorization", "Bearer " + devToken()))
        .andExpect(status().isOk())
        // 계측이 늘고 줄어도 흔들리지 않도록, 특정 지표가 아니라 JVM 기본 지표로 응답을 확인한다.
        .andExpect(jsonPath("$.names").value(hasItem("jvm.memory.used")));
  }

  @Test
  void keepsHealthPublic() throws Exception {
    mockMvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
  }

  private String devToken() throws Exception {
    String response =
        mockMvc
            .perform(
                post("/api/dev/auth/token")
                    .header(SECRET_HEADER, SECRET)
                    .header(API_VERSION_HEADER, API_VERSION))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return JsonPath.read(response, "$.access_token");
  }
}
