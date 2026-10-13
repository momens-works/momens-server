package works.momens.server.context;

/**
 * 연결 쓰기에 사용하는 엔티티 종류입니다.
 *
 * <p>공유 테이블에는 worker·레거시가 쓰는 다른 값도 있으므로 조회 엔티티는 문자열을 유지합니다.
 */
public enum EntityType {
  TASK,
  MEMORY,
  SOURCE_OBJECT
}
