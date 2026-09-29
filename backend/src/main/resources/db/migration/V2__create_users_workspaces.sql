-- 설계서 §12.3 users/workspaces/workspace_members, §17.2.3 must_change_password.
CREATE TABLE users (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email                  VARCHAR(320) NOT NULL,
    email_normalized       VARCHAR(320) NOT NULL,
    display_name           VARCHAR(100) NOT NULL,
    password_hash          VARCHAR(255) NOT NULL,
    status                 VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
    must_change_password   BOOLEAN NOT NULL DEFAULT false,
    password_changed_at    TIMESTAMPTZ,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    deletion_requested_at  TIMESTAMPTZ,
    CONSTRAINT uq_users__email_normalized UNIQUE (email_normalized),
    CONSTRAINT ck_users__status CHECK (status IN ('ACTIVE', 'DELETION_PENDING'))
);

CREATE INDEX ix_users__status ON users (status);
CREATE INDEX ix_users__deletion_requested_at ON users (deletion_requested_at) WHERE deletion_requested_at IS NOT NULL;

CREATE TABLE workspaces (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name       VARCHAR(100) NOT NULL,
    slug       VARCHAR(100) NOT NULL,
    plan       VARCHAR(30) NOT NULL DEFAULT 'PERSONAL',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_workspaces__slug UNIQUE (slug)
);

-- 개인 MVP는 가입 시 OWNER 1행만 생성한다(§12.3). role 확장은 Team 단계(§24.3)에서 다룬다.
CREATE TABLE workspace_members (
    workspace_id UUID NOT NULL REFERENCES workspaces (id),
    user_id      UUID NOT NULL REFERENCES users (id),
    role         VARCHAR(30) NOT NULL DEFAULT 'OWNER',
    joined_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_workspace_members PRIMARY KEY (workspace_id, user_id),
    CONSTRAINT ck_workspace_members__role CHECK (role = 'OWNER')
);

CREATE INDEX ix_workspace_members__user_id ON workspace_members (user_id, workspace_id);
