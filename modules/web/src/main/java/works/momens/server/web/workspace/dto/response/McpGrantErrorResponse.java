package works.momens.server.web.workspace.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "MCP 연결 관리의 레거시 호환 오류 응답")
public record McpGrantErrorResponse(@Schema(example = "forbidden") String error) {}
