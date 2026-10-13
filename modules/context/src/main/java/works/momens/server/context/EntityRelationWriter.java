package works.momens.server.context;

import java.util.UUID;

public interface EntityRelationWriter {

  /**
   * 연결을 활성 상태로 만듭니다. 호출자의 트랜잭션에 참여합니다.
   *
   * <p>레거시 {@code relation/repository.go}의 {@code LinkWithType}과 같은 upsert 시맨틱입니다. 같은 (workspace,
   * from, relation, to) 연결이 이미 살아 있으면 아무것도 하지 않고, 소프트 삭제돼 있으면 되살리며, 없을 때만 새로 만듭니다. {@code
   * entity_relations}에는 UNIQUE 제약이 없어 조건 없이 INSERT 하면 중복 행이 조용히 쌓입니다.
   *
   * @return 활성 상태가 된 연결의 식별자
   */
  UUID link(EntityRelationCommand command);

  /**
   * 삭제되지 않은 연결을 소프트 삭제합니다. 호출자의 트랜잭션에 참여합니다.
   *
   * <p>레거시 {@code relation/repository.go}의 {@code Unlink}와 같은 동작입니다. 조건에 맞고 {@code deleted_at}이
   * {@code NULL}인 행만 갱신하므로 같은 연결이 여러 행으로 중복되어 있으면 한 번에 모두 삭제합니다.
   *
   * @return 하나 이상의 연결을 삭제했으면 {@code true}, 삭제할 연결이 없었으면 {@code false}
   */
  boolean unlink(EntityRelationCommand command);
}
