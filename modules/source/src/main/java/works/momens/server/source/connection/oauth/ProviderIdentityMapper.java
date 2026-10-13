package works.momens.server.source.connection.oauth;

import java.util.Map;

@FunctionalInterface
interface ProviderIdentityMapper {

  ProviderIdentity map(Map<String, Object> tokenResponse, Map<String, Object> identityBody);
}
