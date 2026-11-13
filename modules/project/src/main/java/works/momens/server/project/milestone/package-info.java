/**
 * milestone 하위 도메인.
 *
 * <p>마일스톤과 소유자 aggregate, 생성·조회 영속성을 소유합니다. project core와는 core root의 공개 계약으로만 협력합니다. 워크스페이스 범위의
 * 조회는 호출자가 project core에서 확보한 project id를 받아 처리합니다.
 */
@NamedInterface
package works.momens.server.project.milestone;

import org.springframework.modulith.NamedInterface;
