package works.momens.server.mcp.transport.internal;

import java.util.List;

final class McpProtocol {

  static final String VERSION = "2026-07-28";

  /** 첫 항목은 initialize에서 지원하지 않는 버전을 요청받았을 때 대신 제시하는 버전입니다. */
  static final List<String> LEGACY_VERSIONS = List.of("2025-11-25", "2025-06-18");

  static final String VERSION_HEADER = "MCP-Protocol-Version";
  static final String METHOD_HEADER = "Mcp-Method";
  static final String NAME_HEADER = "Mcp-Name";
  static final String SERVER_NAME = "momens-mcp";
  static final String SERVER_VERSION = "0.1.0";

  private McpProtocol() {}
}
