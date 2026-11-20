package works.momens.server.mcp.transport;

import java.util.Optional;
import tools.jackson.databind.JsonNode;

/** 인증 문맥을 받아 도구를 실행합니다. 등록되지 않은 이름은 빈 값으로 반환합니다. */
public interface McpToolExecutor {
  Optional<JsonNode> call(String name, JsonNode arguments, McpAuthenticationContext context);
}
