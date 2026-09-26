-- SAS JDBC mapping requires both timestamps whenever an access token is present.
-- Validate existing rows too; inconsistent data must be investigated before retrying.
ALTER TABLE oauth2_authorization
    ADD CONSTRAINT chk_mcp_access_token_timestamps
    CHECK (
        access_token_value IS NULL
        OR (access_token_issued_at IS NOT NULL AND access_token_expires_at IS NOT NULL)
    );
