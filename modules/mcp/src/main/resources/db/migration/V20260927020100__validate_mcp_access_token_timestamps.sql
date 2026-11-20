-- Run in a separate transaction from ADD CONSTRAINT to allow concurrent reads and writes.
-- Inconsistent existing data must be investigated before retrying validation.
ALTER TABLE oauth2_authorization
    VALIDATE CONSTRAINT chk_mcp_access_token_timestamps;
