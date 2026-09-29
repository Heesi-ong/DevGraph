-- 설계서 §12.3 knowledge_nodes/tags/node_tags/favorites/node_views/activity_logs (Phase 2).
-- §12.2: 모든 constraint는 이름을 명시하고, 관련 FK는 workspace_id까지 포함해 교차 Workspace 참조를 DB에서 막는다.
-- search_vector/GIN(title trgm) 인덱스는 검색을 구현하는 Phase 5 migration에서 추가한다(expand 방식).
CREATE TABLE knowledge_nodes (
    id           UUID        NOT NULL DEFAULT gen_random_uuid(),
    workspace_id UUID        NOT NULL,
    created_by   UUID        NOT NULL,
    node_type    VARCHAR(20) NOT NULL,
    title        VARCHAR(200) NOT NULL,
    summary      VARCHAR(1000),
    body_md      TEXT,
    status       VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    version      BIGINT      NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    archived_at  TIMESTAMPTZ,
    trashed_at   TIMESTAMPTZ,
    CONSTRAINT pk_knowledge_nodes PRIMARY KEY (id),
    CONSTRAINT uq_knowledge_nodes__workspace_node UNIQUE (workspace_id, id),
    CONSTRAINT fk_knowledge_nodes__workspaces FOREIGN KEY (workspace_id) REFERENCES workspaces (id),
    CONSTRAINT fk_knowledge_nodes__users FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT ck_knowledge_nodes__node_type CHECK (
        node_type IN ('CONCEPT', 'NOTE', 'SNIPPET', 'ERROR', 'SOLUTION', 'RESOURCE', 'PROJECT')),
    CONSTRAINT ck_knowledge_nodes__status CHECK (status IN ('ACTIVE', 'ARCHIVED', 'TRASHED'))
);

CREATE INDEX ix_knowledge_nodes__workspace_status_updated ON knowledge_nodes (workspace_id, status, updated_at DESC, id);
CREATE INDEX ix_knowledge_nodes__workspace_type_status ON knowledge_nodes (workspace_id, node_type, status, updated_at DESC);

CREATE TABLE tags (
    id              UUID         NOT NULL DEFAULT gen_random_uuid(),
    workspace_id    UUID         NOT NULL,
    name            VARCHAR(50)  NOT NULL,
    normalized_name VARCHAR(50)  NOT NULL,
    color           VARCHAR(7),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_tags PRIMARY KEY (id),
    CONSTRAINT uq_tags__workspace_tag UNIQUE (workspace_id, id),
    CONSTRAINT uq_tags__workspace_name UNIQUE (workspace_id, normalized_name),
    CONSTRAINT fk_tags__workspaces FOREIGN KEY (workspace_id) REFERENCES workspaces (id)
);

CREATE TABLE node_tags (
    workspace_id UUID        NOT NULL,
    node_id      UUID        NOT NULL,
    tag_id       UUID        NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_node_tags PRIMARY KEY (node_id, tag_id),
    CONSTRAINT fk_node_tags__knowledge_nodes FOREIGN KEY (workspace_id, node_id)
        REFERENCES knowledge_nodes (workspace_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_node_tags__tags FOREIGN KEY (workspace_id, tag_id)
        REFERENCES tags (workspace_id, id) ON DELETE CASCADE
);

CREATE INDEX ix_node_tags__workspace_tag_node ON node_tags (workspace_id, tag_id, node_id);

CREATE TABLE favorites (
    workspace_id UUID        NOT NULL,
    user_id      UUID        NOT NULL,
    node_id      UUID        NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_favorites PRIMARY KEY (user_id, node_id),
    CONSTRAINT fk_favorites__users FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_favorites__knowledge_nodes FOREIGN KEY (workspace_id, node_id)
        REFERENCES knowledge_nodes (workspace_id, id) ON DELETE CASCADE
);

CREATE INDEX ix_favorites__workspace_user_created ON favorites (workspace_id, user_id, created_at DESC);

-- 최근 조회. ActivityLog와 분리해 조회 이벤트 폭증을 막는다(§12.3).
CREATE TABLE node_views (
    workspace_id   UUID        NOT NULL,
    user_id        UUID        NOT NULL,
    node_id        UUID        NOT NULL,
    last_viewed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    view_count     BIGINT      NOT NULL DEFAULT 1,
    CONSTRAINT pk_node_views PRIMARY KEY (user_id, node_id),
    CONSTRAINT fk_node_views__users FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_node_views__knowledge_nodes FOREIGN KEY (workspace_id, node_id)
        REFERENCES knowledge_nodes (workspace_id, id) ON DELETE CASCADE
);

CREATE INDEX ix_node_views__workspace_user_viewed ON node_views (workspace_id, user_id, last_viewed_at DESC);

-- object_id는 삭제된 대상의 id를 보존해야 하므로 FK를 걸지 않는다(§11.3).
CREATE TABLE activity_logs (
    id            UUID         NOT NULL DEFAULT gen_random_uuid(),
    workspace_id  UUID         NOT NULL,
    actor_user_id UUID         NOT NULL,
    action        VARCHAR(60)  NOT NULL,
    object_type   VARCHAR(30)  NOT NULL,
    object_id     UUID         NOT NULL,
    safe_metadata JSONB,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_activity_logs PRIMARY KEY (id)
);

CREATE INDEX ix_activity_logs__workspace_created ON activity_logs (workspace_id, created_at DESC);
CREATE INDEX ix_activity_logs__workspace_object ON activity_logs (workspace_id, object_type, object_id, created_at DESC);
