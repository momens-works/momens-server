package works.momens.server.mcp.tools.internal;

/** 도구 사용자가 입력을 수정해 재시도할 수 있는 오류입니다. */
class McpToolInputException extends RuntimeException {
  McpToolInputException(String message) {
    super(message);
  }
}
