package works.momens.server.web.workspace;

import java.security.Principal;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import works.momens.server.common.api.BusinessException;
import works.momens.server.common.api.CurrentUser;
import works.momens.server.common.api.ErrorResponse;
import works.momens.server.web.workspace.dto.response.McpGrantErrorResponse;
import works.momens.server.web.workspace.dto.response.McpGrantsResponse;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/mcp-grants")
@RequiredArgsConstructor
class McpGrantController implements McpGrantControllerDocs {
  private final WorkspaceMcpGrantService service;

  @Override
  @GetMapping(version = "1")
  public McpGrantsResponse list(@PathVariable UUID workspaceId, Principal principal) {
    return new McpGrantsResponse(service.list(workspaceId, CurrentUser.id(principal)));
  }

  @Override
  @DeleteMapping(path = "/{grantId}", version = "1")
  public ResponseEntity<Void> revoke(
      @PathVariable UUID workspaceId, @PathVariable UUID grantId, Principal principal) {
    service.revoke(workspaceId, CurrentUser.id(principal), grantId);
    return ResponseEntity.noContent().build();
  }

  @ExceptionHandler(WorkspaceMcpGrantService.AccessDenied.class)
  ResponseEntity<McpGrantErrorResponse> forbidden() {
    return ResponseEntity.status(403).body(new McpGrantErrorResponse("forbidden"));
  }

  @ExceptionHandler(BusinessException.class)
  ResponseEntity<?> businessError(BusinessException exception) {
    var code = exception.getErrorCode();
    if (code.status() == 403 || code.status() == 404) {
      return forbidden();
    }
    return ResponseEntity.status(code.status())
        .body(ErrorResponse.of(code.code(), exception.getMessage(), exception.getDetails()));
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  ResponseEntity<McpGrantErrorResponse> invalidId(MethodArgumentTypeMismatchException exception) {
    String message =
        "grantId".equals(exception.getName()) ? "invalid grant id" : "invalid workspace id";
    return ResponseEntity.badRequest().body(new McpGrantErrorResponse(message));
  }
}
