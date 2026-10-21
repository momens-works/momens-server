/**
 * 소스 연동 하위 도메인입니다.
 *
 * <p>워크스페이스에 붙은 외부 소스 연동({@code source_connections})과 그 자격 증명({@code source_credentials}), 그리고 연동을
 * 만드는 OAuth 설치 흐름을 함께 소유합니다. 자격 증명은 OAuth 콜백에서 만들어지고 콜백과 provider token 갱신에서만 바뀌므로, 연동 영속성과 OAuth를
 * 같은 경계에 둡니다.
 *
 * <p>변경 이유가 source-ref와 다릅니다. 이쪽은 제공자(provider) 규격과 토큰 취급이 바뀔 때 움직이고, source-ref는 붙여넣은 자료의 표현과 검증이
 * 바뀔 때 움직입니다.
 *
 * <p>공개 계약은 {@code source} 모듈 root에 그대로 둡니다. nested application module은 다른 상위 모듈이 참조할 수 없으므로 이 패키지는
 * 외부에 아무것도 공개하지 않습니다.
 */
@ApplicationModule
package works.momens.server.source.connection;

import org.springframework.modulith.ApplicationModule;
