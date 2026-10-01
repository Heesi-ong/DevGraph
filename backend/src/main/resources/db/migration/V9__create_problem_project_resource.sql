-- 설계서 §12.3 error_records / solution_records / projects / resources (Phase 6).
-- 모두 knowledge_nodes의 1:1 subtype이다. parent 타입 일치는 trigger 없이 정합성 검사 SQL + 통합 테스트로 보장한다.

CREATE TABLE error_records (
    node_id               UUID         NOT NULL,
    workspace_id          UUID         NOT NULL,
    error_message         TEXT         NOT NULL,
    environment           VARCHAR(500),
    reproduction_steps_md TEXT,
    cause_hypothesis_md   TEXT,
    resolution_status     VARCHAR(20)  NOT NULL DEFAULT 'OPEN',
    occurred_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    resolved_at           TIMESTAMPTZ,
    -- 검색(§15.4): 메시지 B, 환경·재현·원인 C. 긴 텍스트는 tsvector 1 MB 한계 때문에 앞부분만 색인한다.
    search_vector         tsvector GENERATED ALWAYS AS (
        setweight(to_tsvector('simple'::regconfig, left(error_message, 20000)), 'B')
        || setweight(to_tsvector('simple'::regconfig,
               coalesce(environment, '') || ' ' || coalesce(left(reproduction_steps_md, 50000), '')
               || ' ' || coalesce(left(cause_hypothesis_md, 50000), '')), 'C')) STORED,
    CONSTRAINT pk_error_records PRIMARY KEY (node_id),
    CONSTRAINT fk_error_records__knowledge_nodes FOREIGN KEY (workspace_id, node_id)
        REFERENCES knowledge_nodes (workspace_id, id) ON DELETE CASCADE,
    CONSTRAINT ck_error_records__status CHECK (resolution_status IN ('OPEN', 'INVESTIGATING', 'RESOLVED', 'WONT_FIX')),
    CONSTRAINT ck_error_records__message CHECK (length(btrim(error_message)) > 0 AND octet_length(error_message) <= 100000),
    -- RESOLVED일 때만 resolved_at이 있다(전환 시 서버가 맞춘다).
    CONSTRAINT ck_error_records__resolved_at CHECK ((resolution_status = 'RESOLVED') = (resolved_at IS NOT NULL))
);

CREATE INDEX ix_error_records__status_occurred ON error_records (workspace_id, resolution_status, occurred_at DESC);
CREATE INDEX ix_error_records__message_trgm ON error_records USING gin (error_message gin_trgm_ops);
CREATE INDEX ix_error_records__search_vector ON error_records USING gin (search_vector);

CREATE TABLE solution_records (
    node_id         UUID         NOT NULL,
    workspace_id    UUID         NOT NULL,
    approach_md     TEXT         NOT NULL,
    steps_md        TEXT,
    verification_md TEXT,
    tradeoffs_md    TEXT,
    resolved_at     TIMESTAMPTZ,
    search_vector   tsvector GENERATED ALWAYS AS (
        setweight(to_tsvector('simple'::regconfig, left(approach_md, 50000)), 'B')
        || setweight(to_tsvector('simple'::regconfig,
               coalesce(left(steps_md, 50000), '') || ' ' || coalesce(left(verification_md, 50000), '')
               || ' ' || coalesce(left(tradeoffs_md, 50000), '')), 'C')) STORED,
    CONSTRAINT pk_solution_records PRIMARY KEY (node_id),
    CONSTRAINT fk_solution_records__knowledge_nodes FOREIGN KEY (workspace_id, node_id)
        REFERENCES knowledge_nodes (workspace_id, id) ON DELETE CASCADE,
    CONSTRAINT ck_solution_records__approach CHECK (length(btrim(approach_md)) > 0 AND octet_length(approach_md) <= 100000)
);

CREATE INDEX ix_solution_records__resolved ON solution_records (workspace_id, resolved_at DESC);
CREATE INDEX ix_solution_records__search_vector ON solution_records USING gin (search_vector);

CREATE TABLE projects (
    node_id        UUID         NOT NULL,
    workspace_id   UUID         NOT NULL,
    project_status VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    repository_url VARCHAR(500),
    started_on     DATE,
    ended_on       DATE,
    CONSTRAINT pk_projects PRIMARY KEY (node_id),
    CONSTRAINT fk_projects__knowledge_nodes FOREIGN KEY (workspace_id, node_id)
        REFERENCES knowledge_nodes (workspace_id, id) ON DELETE CASCADE,
    CONSTRAINT ck_projects__status CHECK (project_status IN ('ACTIVE', 'PAUSED', 'COMPLETED', 'ARCHIVED')),
    CONSTRAINT ck_projects__period CHECK (started_on IS NULL OR ended_on IS NULL OR ended_on >= started_on),
    CONSTRAINT ck_projects__repository_url CHECK (repository_url IS NULL OR repository_url ~* '^https?://')
);

CREATE INDEX ix_projects__status_started ON projects (workspace_id, project_status, started_on DESC);

CREATE TABLE resources (
    node_id         UUID         NOT NULL,
    workspace_id    UUID         NOT NULL,
    url             VARCHAR(2000) NOT NULL,
    url_normalized  VARCHAR(2000) NOT NULL,
    resource_kind   VARCHAR(20)  NOT NULL DEFAULT 'WEB',
    site_name       VARCHAR(200),
    last_checked_at TIMESTAMPTZ,
    CONSTRAINT pk_resources PRIMARY KEY (node_id),
    CONSTRAINT fk_resources__knowledge_nodes FOREIGN KEY (workspace_id, node_id)
        REFERENCES knowledge_nodes (workspace_id, id) ON DELETE CASCADE,
    CONSTRAINT ck_resources__kind CHECK (resource_kind IN ('WEB', 'DOC', 'VIDEO', 'REPO', 'BOOK', 'OTHER')),
    -- §17.4: http/https만. javascript:, data:, file: 등은 DB에서도 막는다(최종 방어선).
    CONSTRAINT ck_resources__url_scheme CHECK (url ~* '^https?://')
);

-- 중복 URL은 경고만 하고 저장을 막지 않으므로 UNIQUE가 아니라 조회용 index다(§12.3).
CREATE INDEX ix_resources__workspace_url ON resources (workspace_id, url_normalized);
