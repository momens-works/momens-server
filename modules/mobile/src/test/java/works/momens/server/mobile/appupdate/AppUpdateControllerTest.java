package works.momens.server.mobile.appupdate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.config.annotation.ApiVersionConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import works.momens.server.common.api.FieldValidationDetails;
import works.momens.server.common.api.FieldValidationException;
import works.momens.server.common.api.JacksonEnumConverter;

@WebMvcTest(AppUpdateController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({JacksonEnumConverter.class, AppUpdateControllerTest.TestConfig.class})
class AppUpdateControllerTest {

  @Autowired private MockMvc mockMvc;

  @Test
  void respondsUpdateRequiredForVersionBelowMinimum() throws Exception {
    mockMvc
        .perform(
            get("/api/mobile/app-update")
                .header("API-Version", "1")
                .param("platform", "android")
                .param("app_version", "1.1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.update_required").value(true));
  }

  @Test
  void rejectsMalformedAppVersionWithFieldName() {
    assertThatThrownBy(
            () ->
                mockMvc.perform(
                    get("/api/mobile/app-update")
                        .header("API-Version", "1")
                        .param("platform", "ios")
                        .param("app_version", "1.0-beta")))
        .cause()
        .isInstanceOfSatisfying(
            FieldValidationException.class,
            e ->
                assertThat(((FieldValidationDetails) e.getDetails()).fields())
                    .extracting(FieldValidationDetails.FieldViolation::field)
                    .containsExactly("app_version"));
  }

  @TestConfiguration
  static class TestConfig implements WebMvcConfigurer {

    @Override
    public void configureApiVersioning(ApiVersionConfigurer configurer) {
      configurer.useRequestHeader("API-Version").addSupportedVersions("1").setDefaultVersion("1");
    }

    @Bean
    AppUpdatePolicy appUpdatePolicy() {
      return new AppUpdatePolicy(
          Map.of(
              AppPlatform.ANDROID, new AppVersion(1, 2, 0),
              AppPlatform.IOS, new AppVersion(1, 2, 0)));
    }
  }
}
