package works.momens.server.source.connection.oauth;

import java.util.LinkedHashMap;
import java.util.Map;

final class ProviderIdentities {

  private ProviderIdentities() {}

  static ProviderIdentity github(
      Map<String, Object> tokenResponse, Map<String, Object> identityBody) {
    String login = string(identityBody, "login");
    if (login.isEmpty()) {
      throw new IllegalStateException("github identity missing login");
    }
    String name = string(identityBody, "name");
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("github_user_id", identityBody.get("id"));
    metadata.put("html_url", string(identityBody, "html_url"));
    metadata.put("login", login);
    return new ProviderIdentity(login, name.isEmpty() ? login : name, metadata);
  }

  static ProviderIdentity slack(
      Map<String, Object> tokenResponse, Map<String, Object> identityBody) {
    String teamId = string(identityBody, "team_id");
    if (!Boolean.TRUE.equals(identityBody.get("ok")) || teamId.isEmpty()) {
      throw new IllegalStateException("slack identity missing team");
    }
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("team_url", string(identityBody, "url"));
    return new ProviderIdentity(teamId, string(identityBody, "team"), metadata);
  }

  static ProviderIdentity notion(
      Map<String, Object> tokenResponse, Map<String, Object> identityBody) {
    String workspaceId = string(tokenResponse, "workspace_id");
    if (workspaceId.isEmpty()) {
      throw new IllegalStateException("notion identity missing workspace_id");
    }
    String workspaceName = string(tokenResponse, "workspace_name");
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("workspace_icon", string(tokenResponse, "workspace_icon"));
    return new ProviderIdentity(
        workspaceId, workspaceName.isEmpty() ? workspaceId : workspaceName, metadata);
  }

  static ProviderIdentity figma(
      Map<String, Object> tokenResponse, Map<String, Object> identityBody) {
    String userId = string(tokenResponse, "user_id_string");
    if (userId.isEmpty()) {
      throw new IllegalStateException("figma identity missing user_id");
    }
    return new ProviderIdentity(userId, "Figma user " + userId, Map.of());
  }

  private static String string(Map<String, Object> body, String key) {
    Object value = body == null ? null : body.get(key);
    return value == null ? "" : String.valueOf(value);
  }
}
