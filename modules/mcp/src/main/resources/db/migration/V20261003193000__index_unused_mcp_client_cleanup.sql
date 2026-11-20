CREATE INDEX idx_mcp_client_issued_at
    ON oauth2_registered_client (client_id_issued_at, id);

CREATE INDEX idx_mcp_authorization_registered_client
    ON oauth2_authorization (registered_client_id);

-- Include revoked grants: a previously authorized client is never an unused registration.
CREATE INDEX idx_mcp_grants_client ON mcp_grants (client_id);
