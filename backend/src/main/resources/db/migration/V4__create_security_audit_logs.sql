-- 설계서 §12.3/§15.2: 보안 감사 이벤트는 activity_logs와 분리하고 REQUIRES_NEW로 즉시 커밋한다.
CREATE TABLE security_audit_logs (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id    UUID,
    actor_user_id   UUID,
    event_type      VARCHAR(60) NOT NULL,
    ip_prefix       VARCHAR(45),
    user_agent_hash BYTEA,
    outcome         VARCHAR(10) NOT NULL,
    metadata        JSONB,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_security_audit_logs__outcome CHECK (outcome IN ('SUCCESS', 'FAILURE'))
);

CREATE INDEX ix_security_audit_logs__workspace ON security_audit_logs (workspace_id, created_at DESC);
CREATE INDEX ix_security_audit_logs__actor ON security_audit_logs (actor_user_id, event_type, created_at DESC);
