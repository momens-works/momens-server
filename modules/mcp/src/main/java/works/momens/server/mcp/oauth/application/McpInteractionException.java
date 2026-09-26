package works.momens.server.mcp.oauth.application;

import lombok.Getter;

@Getter
public final class McpInteractionException extends RuntimeException {
  private final Reason reason;

  McpInteractionException(Reason reason) {
    super(reason.name());
    this.reason = reason;
  }

  public enum Reason {
    INVALID_INTERACTION,
    WORKSPACE_MEMBERSHIP_REQUIRED,
    EXPIRED,
    ALREADY_DECIDED
  }
}
