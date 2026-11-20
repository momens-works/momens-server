package works.momens.server.minsu.draft.generation;

/**
 * 한 번의 생성 시도가 어느 경로에서 일어나는지.
 *
 * <p>두 경로는 같은 프롬프트와 같은 검증을 쓰지만 <b>timeout이 다르다.</b> 동기는 원탭 action의 사용자 체감으로 정한 값이고, 비동기는 아무도 기다리지
 * 않으므로 더 크다. 관측에서도 둘을 나눠 봐야 하므로 같은 값이 tag로 나간다.
 */
enum GenerationMode {
  SYNCHRONOUS("sync"),
  ASYNCHRONOUS("async");

  private final String tag;

  GenerationMode(String tag) {
    this.tag = tag;
  }

  String tag() {
    return tag;
  }
}
