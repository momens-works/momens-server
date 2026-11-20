-- Each SAS authorization is a token family. Keep used refresh digests to detect replay.
CREATE TABLE mcp_token_families (
    id uuid PRIMARY KEY,
    authorization_id varchar(100) NOT NULL UNIQUE REFERENCES oauth2_authorization(id) ON DELETE CASCADE,
    grant_id uuid NOT NULL REFERENCES mcp_grants(id),
    revoked_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT NOW(),
    updated_at timestamptz NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_mcp_token_families_grant ON mcp_token_families(grant_id);
CREATE TABLE mcp_refresh_token_history (
    id uuid PRIMARY KEY,
    token_hash varchar(64) NOT NULL UNIQUE,
    authorization_id varchar(100) NOT NULL REFERENCES mcp_token_families(authorization_id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL DEFAULT NOW(),
    updated_at timestamptz NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_mcp_authorization_code ON oauth2_authorization(authorization_code_value);
CREATE INDEX idx_mcp_access_token ON oauth2_authorization(access_token_value);
CREATE INDEX idx_mcp_refresh_token ON oauth2_authorization(refresh_token_value);
