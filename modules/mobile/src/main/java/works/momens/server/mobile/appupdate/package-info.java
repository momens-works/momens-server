/**
 * 모바일 앱 업데이트 확인 하위 도메인입니다.
 *
 * <p>{@code GET /api/mobile/app-update}가 담당하는 경계를 mobile 모듈의 Spring Modulith nested 모듈로 정의합니다. 플랫폼별
 * 최소 지원 버전 설정, 업데이트 필요 여부를 판정하는 정책, 컨트롤러를 이 패키지에서 관리하며 외부에 공개하는 계약은 없습니다.
 */
@ApplicationModule
package works.momens.server.mobile.appupdate;

import org.springframework.modulith.ApplicationModule;
