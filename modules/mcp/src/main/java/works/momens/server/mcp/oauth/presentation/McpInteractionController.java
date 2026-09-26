package works.momens.server.mcp.oauth.presentation;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import works.momens.server.common.api.BusinessException;
import works.momens.server.common.api.CommonErrorCode;
import works.momens.server.common.api.CurrentUser;
import works.momens.server.mcp.oauth.application.McpInteractionException;
import works.momens.server.mcp.oauth.application.McpInteractionService;

@RestController
@RequestMapping(path = "/api/oauth/interactions", version = "1")
@RequiredArgsConstructor
class McpInteractionController implements McpInteractionControllerDocs {
  private final McpInteractionService interactions;

  @GetMapping("/{id}")
  public InteractionResponse get(Principal principal, @PathVariable UUID id) {
    return new InteractionResponse(
        InteractionView.from(interactions.get(id, CurrentUser.id(principal))));
  }

  @PostMapping("/{id}/approve")
  public RedirectResponse approve(
      Principal principal, @PathVariable UUID id, @Valid @RequestBody ApproveRequest request) {
    return new RedirectResponse(
        interactions.approve(id, CurrentUser.id(principal), request.workspaceId()));
  }

  @PostMapping("/{id}/deny")
  public RedirectResponse deny(Principal principal, @PathVariable UUID id) {
    return new RedirectResponse(interactions.deny(id, CurrentUser.id(principal)));
  }

  @ExceptionHandler(McpInteractionException.class)
  ResponseEntity<Map<String, String>> handle(McpInteractionException exception) {
    return switch (exception.getReason()) {
      case INVALID_INTERACTION ->
          ResponseEntity.badRequest().body(Map.of("error", "invalid_interaction"));
      case WORKSPACE_MEMBERSHIP_REQUIRED ->
          ResponseEntity.status(403).body(Map.of("error", "forbidden"));
      case EXPIRED -> ResponseEntity.status(410).body(Map.of("error", "interaction_expired"));
      case ALREADY_DECIDED ->
          ResponseEntity.status(409).body(Map.of("error", "interaction_decided"));
      default ->
          throw new IllegalStateException("Unmapped interaction failure: " + exception.getReason());
    };
  }

  @Schema(name = "McpApproveInteractionRequest")
  record ApproveRequest(@NotNull UUID workspaceId) {}

  @Schema(name = "McpInteractionResponse")
  record InteractionResponse(InteractionView interaction) {}

  @Schema(name = "McpInteractionView")
  record InteractionView(
      UUID interactionId,
      String clientName,
      String redirectHost,
      List<String> scopes,
      List<WorkspaceOption> workspaces,
      Instant expiresAt) {
    static InteractionView from(McpInteractionService.InteractionView view) {
      return new InteractionView(
          view.interactionId(),
          view.clientName(),
          view.redirectHost(),
          view.scopes(),
          view.workspaces().stream()
              .map(
                  workspace ->
                      new WorkspaceOption(workspace.id(), workspace.name(), workspace.slug()))
              .toList(),
          view.expiresAt());
    }
  }

  @Schema(name = "McpInteractionWorkspace")
  record WorkspaceOption(UUID id, String name, String slug) {}

  @Schema(name = "McpInteractionRedirectResponse")
  record RedirectResponse(String redirectTo) {}

  @ExceptionHandler({
    MethodArgumentNotValidException.class,
    MethodArgumentTypeMismatchException.class,
    HttpMessageNotReadableException.class
  })
  ResponseEntity<Map<String, String>> invalidRequest(Exception exception) {
    return ResponseEntity.badRequest().body(Map.of("error", "invalid_interaction"));
  }

  @ExceptionHandler(BusinessException.class)
  ResponseEntity<Map<String, String>> grantError(BusinessException exception) {
    if (exception.getErrorCode() == CommonErrorCode.AUTH_FORBIDDEN) {
      return ResponseEntity.status(403).body(Map.of("error", "forbidden"));
    }
    if (exception.getErrorCode() == CommonErrorCode.COMMON_VALIDATION_FAILED) {
      return ResponseEntity.badRequest().body(Map.of("error", "invalid_interaction"));
    }
    throw exception;
  }
}
