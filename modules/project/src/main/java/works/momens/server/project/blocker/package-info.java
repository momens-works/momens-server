/**
 * blocker 하위 도메인.
 *
 * <p>blocker의 조회·상태 변경과 영속성을 소유하며, 다른 경계에는 blocker root의 {@link
 * works.momens.server.project.blocker.BlockerReader}와 {@link
 * works.momens.server.project.blocker.BlockerWriter}를 공개합니다.
 */
@NamedInterface
package works.momens.server.project.blocker;

import org.springframework.modulith.NamedInterface;
