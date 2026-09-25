package works.momens.server.mobile.pushdevice;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.security.Principal;
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
import works.momens.server.notification.PushDeviceRegistrar;
import works.momens.server.notification.PushInstallationPlatform;

/**
 * 컨트롤러가 경로 변수·요청 body·Principal을 notification public API에 그대로 전달하고 204를 내는지, 요청 검증 위반이 400으로 거부되는지
 * 검증합니다. versioning은 모듈 경계상 app 설정을 못 가져오므로 슬라이스 안에서 동일하게 구성합니다.
 */
@WebMvcTest(PushDeviceController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(PushDeviceControllerTest.ApiVersioningTestConfig.class)
class PushDeviceControllerTest {

  @Autowired private MockMvc mockMvc;
  @MockitoBean private PushDeviceRegistrar pushDeviceRegistrar;

  private static final UUID USER_ID = UUID.fromString("5d2f7f3a-5db1-4f2c-8b9e-13607dd1f5e8");
  private static final String FID = "fid-123";
  private final Principal principal = USER_ID::toString;

  @Test
  @DisplayName("설치 등록은 204를 내고 notification public API에 위임한다")
  void registerDelegatesAndReturnsNoContent() throws Exception {
    mockMvc
        .perform(
            put("/api/me/push-devices/{firebaseInstallationId}", FID)
                .principal(principal)
                .header("API-Version", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"fcm_registration_token": "token-1", "platform": "android"}
                    """))
        .andExpect(status().isNoContent());

    verify(pushDeviceRegistrar).register(USER_ID, FID, "token-1", PushInstallationPlatform.ANDROID);
  }

  @Test
  @DisplayName("android가 아닌 platform은 400으로 거부한다")
  void registerRejectsNonAndroidPlatform() throws Exception {
    mockMvc
        .perform(
            put("/api/me/push-devices/{firebaseInstallationId}", FID)
                .principal(principal)
                .header("API-Version", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"fcm_registration_token": "token-1", "platform": "ios"}
                    """))
        .andExpect(status().isBadRequest());

    verifyNoInteractions(pushDeviceRegistrar);
  }

  @Test
  @DisplayName("공백 token은 400으로 거부한다")
  void registerRejectsBlankToken() throws Exception {
    mockMvc
        .perform(
            put("/api/me/push-devices/{firebaseInstallationId}", FID)
                .principal(principal)
                .header("API-Version", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"fcm_registration_token": " ", "platform": "android"}
                    """))
        .andExpect(status().isBadRequest());

    verifyNoInteractions(pushDeviceRegistrar);
  }

  @Test
  @DisplayName("설치 해제는 204를 내고 notification public API에 위임한다")
  void unregisterDelegatesAndReturnsNoContent() throws Exception {
    mockMvc
        .perform(
            delete("/api/me/push-devices/{firebaseInstallationId}", FID)
                .principal(principal)
                .header("API-Version", "1"))
        .andExpect(status().isNoContent());

    verify(pushDeviceRegistrar).unregister(USER_ID, FID);
  }

  @TestConfiguration
  static class ApiVersioningTestConfig implements WebMvcConfigurer {
    @Override
    public void configureApiVersioning(ApiVersionConfigurer configurer) {
      configurer.useRequestHeader("API-Version").addSupportedVersions("1").setDefaultVersion("1");
    }
  }
}
