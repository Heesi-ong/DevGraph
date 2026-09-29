-- 설계서 §17.2.1/§12.3: 기기 세션(family)과 rotation 이력을 분리한다.
CREATE TABLE auth_session_families (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID NOT NULL REFERENCES users (id),
    device_label        VARCHAR(100),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_rotated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    absolute_expires_at TIMESTAMPTZ NOT NULL,
    revoked_at          TIMESTAMPTZ,
    revoke_reason       VARCHAR(40),
    CONSTRAINT ck_auth_session_families__revoke_reason CHECK (
        revoke_reason IN ('USER_REQUEST', 'PASSWORD_CHANGED', 'ACCOUNT_DELETION_REQUESTED', 'REUSE_DETECTED', 'ABSOLUTE_EXPIRED')
    )
);

CREATE INDEX ix_auth_session_families__user_id ON auth_session_families (user_id, revoked_at);

-- rotation 이력. 사용자에게 노출하는 세션 식별자가 아니다(그건 family.id, §17.2.1).
CREATE TABLE auth_sessions (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    family_id           UUID NOT NULL REFERENCES auth_session_families (id),
    refresh_token_hash  BYTEA NOT NULL,
    user_agent_hash     BYTEA,
    ip_prefix           VARCHAR(45),
    expires_at          TIMESTAMPTZ NOT NULL,
    rotated_at          TIMESTAMPTZ,
    revoked_at          TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_auth_sessions__refresh_token_hash UNIQUE (refresh_token_hash)
);

CREATE INDEX ix_auth_sessions__family ON auth_sessions (family_id, revoked_at, expires_at);

-- 설계서 §17.2.2: family당 1개, purpose 4종.
CREATE TABLE reauth_tokens (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    token_family_id  UUID NOT NULL REFERENCES auth_session_families (id),
    purpose          VARCHAR(40) NOT NULL,
    target_id        UUID,
    token_hash       BYTEA NOT NULL,
    expires_at       TIMESTAMPTZ NOT NULL,
    used_at          TIMESTAMPTZ,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_reauth_tokens__family UNIQUE (token_family_id),
    CONSTRAINT uq_reauth_tokens__token_hash UNIQUE (token_hash),
    CONSTRAINT ck_reauth_tokens__purpose CHECK (
        purpose IN ('EXPORT_CREATE', 'IMPORT_CREATE', 'NODE_PERMANENT_DELETE', 'ACCOUNT_DELETE_REQUEST')
    )
);
