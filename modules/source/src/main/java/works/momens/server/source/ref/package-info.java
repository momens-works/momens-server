/**
 * source-ref 하위 도메인입니다.
 *
 * <p>태스크·메모리에 붙는 출처 참조({@code source_refs})의 저장과 조회, 검증을 소유합니다. 연동 없이 붙여넣은 주소도 여기서 다루므로 소스 연동에 의존하지
 * 않습니다.
 *
 * <p>공개 계약은 {@code source} 모듈 root에 그대로 둡니다. nested application module은 다른 상위 모듈이 참조할 수 없으므로 이 패키지는
 * 외부에 아무것도 공개하지 않습니다.
 */
@ApplicationModule
package works.momens.server.source.ref;

import org.springframework.modulith.ApplicationModule;
