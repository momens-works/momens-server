/**
 * 웹 사용자 프로필 HTTP 표면.
 *
 * <p>웹 클라이언트가 호출하는 {@code /api/me} 조회·수정 endpoint를 소유합니다(MOM-0852). 사용자 엔티티·프로필 정책은 user 모듈이 소유하고,
 * 이 패키지는 {@link works.momens.server.user.UserService}에 위임하는 얇은 Controller와 요청·응답 DTO만 둡니다.
 *
 * <p>{@code /api/me/push-devices/*}는 앱이 호출하는 표면이라 같은 경로 접두사여도 mobile 모듈이 소유합니다. 소유 단위는 경로 접두사가 아니라
 * 호출 표면입니다.
 */
@ApplicationModule
package works.momens.server.web.user;

import org.springframework.modulith.ApplicationModule;
