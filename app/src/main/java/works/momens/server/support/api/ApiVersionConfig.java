package works.momens.server.support.api;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ApiVersionConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
class ApiVersionConfig implements WebMvcConfigurer {

  static final String API_VERSION_HEADER = "API-Version";
  static final String V1 = "1";

  @Override
  public void configureApiVersioning(ApiVersionConfigurer configurer) {
    // 모든 엔드포인트가 version mapping을 가지며 API-Version 헤더로 분기합니다. 헤더를 보내지 않는
    // 클라이언트가 있어 기본값을 V1로 둡니다. 기본값을 없애면 그 요청은 실패합니다
    // (docs/spec/api-versioning.md, docs/adr/0006).
    configurer.useRequestHeader(API_VERSION_HEADER).addSupportedVersions(V1).setDefaultVersion(V1);
  }
}
