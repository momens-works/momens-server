CREATE TABLE mcp_grants (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    client_id TEXT NOT NULL,
    workspace_id UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    scopes TEXT[] NOT NULL,
    approved_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_mcp_grants_scopes CHECK (
        cardinality(scopes) > 0
        AND scopes <@ ARRAY[
            'mcp:projects:read',
            'mcp:members:read',
            'mcp:milestones:read',
            'mcp:milestones:write',
            'mcp:tasks:read',
            'mcp:tasks:write'
        ]::TEXT[]
    )
);

CREATE UNIQUE INDEX uq_mcp_grants_active_subject
    ON mcp_grants(user_id, client_id, workspace_id)
    WHERE revoked_at IS NULL;

CREATE INDEX idx_mcp_grants_workspace_active
    ON mcp_grants(workspace_id, revoked_at);
