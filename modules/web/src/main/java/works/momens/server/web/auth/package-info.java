/**
 * 웹 인증 HTTP 표면.
 *
 * <p>웹 클라이언트가 호출하는 로그인·세션 갱신·로그아웃 endpoint를 소유합니다(MOM-0852). 인증 로직과 전송 정책(쿠키 속성, 리다이렉트 대상, 실패 코드
 * 매핑)은 auth 모듈이 소유하고, 이 패키지는 {@code WebAuthSession}이 돌려준 결과를 응답으로 옮기기만 합니다.
 */
@ApplicationModule
package works.momens.server.web.auth;

import org.springframework.modulith.ApplicationModule;
