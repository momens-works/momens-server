package works.momens.server.mcp.tools.internal;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import works.momens.server.common.api.BusinessException;
import works.momens.server.common.api.CommonErrorCode;
import works.momens.server.mcp.transport.McpAuthenticationContext;
import works.momens.server.mcp.transport.McpToolCatalog;
import works.momens.server.mcp.transport.McpToolDefinition;
import works.momens.server.mcp.transport.McpToolExecutor;
import works.momens.server.project.task.TaskErrorCode;

@Service
@RequiredArgsConstructor
class McpToolService implements McpToolCatalog, McpToolExecutor {
  private final McpReadToolService reads;
  private final McpWriteToolService writes;
  private final ObjectMapper mapper;

  @Override
  public List<McpToolDefinition> list(McpAuthenticationContext context) {
    return Stream.concat(reads.list(context).stream(), writes.list(context).stream())
        .sorted(Comparator.comparing(McpToolDefinition::name))
        .toList();
  }

  @Override
  public Optional<JsonNode> call(
      String name, JsonNode arguments, McpAuthenticationContext context) {
    Optional<JsonNode> readResult = reads.call(name, arguments, context);
    if (readResult.isPresent()) {
      return readResult;
    }
    // Catch outside the write transaction so both validation and rendering failures roll it back.
    try {
      return writes.call(name, arguments, context);
    } catch (McpToolInputException exception) {
      return Optional.of(error(exception.getMessage()));
    } catch (OptimisticLockingFailureException exception) {
      return Optional.of(
          error("Conflict: this item changed. Read the latest version before editing again."));
    } catch (BusinessException exception) {
      if (exception.getErrorCode() == CommonErrorCode.COMMON_CONCURRENT_MODIFICATION) {
        return Optional.of(
            error("Conflict: this item changed. Read the latest version before editing again."));
      }
      if (exception.getErrorCode() == CommonErrorCode.COMMON_VALIDATION_FAILED) {
        return Optional.of(error("Invalid value or reference for this tool."));
      }
      if (exception.getErrorCode() == CommonErrorCode.COMMON_NOT_FOUND
          || exception.getErrorCode() == TaskErrorCode.TASK_NOT_FOUND) {
        return Optional.of(error("Resource not found in this workspace."));
      }
      if (exception.getErrorCode() == CommonErrorCode.AUTH_FORBIDDEN) {
        return Optional.of(error("You don't have access to that resource."));
      }
      throw exception;
    }
  }

  private ObjectNode error(String text) {
    ObjectNode result = mapper.createObjectNode();
    result.put("resultType", "complete");
    result.putArray("content").addObject().put("type", "text").put("text", text);
    result.put("isError", true);
    return result;
  }
}
