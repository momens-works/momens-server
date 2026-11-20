/**
 * dev 전용 Signal 생성 하위 도메인.
 *
 * <p>signal 모듈 안에서 dev 데모용 Signal 생성({@code POST /api/dev/projects/{projectId}/signals})이 담당하는 경계를
 * Spring Modulith nested 모듈로 명시합니다. 운영 경로에서 Signal 원본은 worker가 생성하고 이 서버는 읽기만 하므로(ADR-0007), 이 패키지는
 * 그 원칙의 dev 한정 예외를 격리합니다.
 *
 * <p>모든 빈은 {@code @DevOnly}로 게이트되어 prod에는 존재하지 않습니다. {@code @Immutable} 읽기 엔티티를 재사용하지 않는 전용 insert
 * 경로를 쓰고, {@code source_refs} 쓰기는 source 모듈의 dev 쓰기 public API에 위임하며, {@code signal.created} outbox
 * 발행은 같은 트랜잭션에서 {@code OutboxAppender}를 호출합니다. 앱이 호출하는 {@code /api/mobile/*} 표면과 달리 dev 도구 표면이라
 * mobile 모듈로 옮기지 않습니다.
 */
@ApplicationModule
package works.momens.server.signal.dev;

import org.springframework.modulith.ApplicationModule;
