/**
 * 모바일 인증 HTTP 표면.
 *
 * <p>앱이 호출하는 Google ID 토큰 교환·재발급·로그아웃 endpoint를 소유합니다(MOM-0852). 인증 로직과 토큰 정책은 auth 모듈이 소유하고, 이
 * 패키지는 {@link works.momens.server.auth.MobileAuthService}에 위임하는 얇은 Controller와 요청·응답 DTO만 둡니다.
 */
@ApplicationModule
package works.momens.server.mobile.auth;

import org.springframework.modulith.ApplicationModule;
