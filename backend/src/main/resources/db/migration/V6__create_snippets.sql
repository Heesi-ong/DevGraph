-- 설계서 §12.3 snippets/snippet_versions (Phase 3).
-- Snippet은 knowledge_nodes(node_type='SNIPPET')의 subtype이다. parent 타입 일치는 trigger 없이
-- 정합성 검사 SQL + 통합 테스트로 보장한다(§12.3 snippets constraint).
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE TABLE snippets (
    node_id             UUID        NOT NULL,
    workspace_id        UUID        NOT NULL,
    language            VARCHAR(30) NOT NULL,
    framework           VARCHAR(50),
    current_version_no  INTEGER     NOT NULL,
    last_used_at        TIMESTAMPTZ,
    use_count           BIGINT      NOT NULL DEFAULT 0,
    secret_scan_status  VARCHAR(30) NOT NULL DEFAULT 'CLEAN',
    CONSTRAINT pk_snippets PRIMARY KEY (node_id),
    CONSTRAINT uq_snippets__workspace_node UNIQUE (workspace_id, node_id),
    CONSTRAINT fk_snippets__knowledge_nodes FOREIGN KEY (workspace_id, node_id)
        REFERENCES knowledge_nodes (workspace_id, id) ON DELETE CASCADE,
    CONSTRAINT ck_snippets__language CHECK (length(language) > 0),
    CONSTRAINT ck_snippets__current_version_no CHECK (current_version_no >= 1),
    CONSTRAINT ck_snippets__use_count CHECK (use_count >= 0),
    CONSTRAINT ck_snippets__secret_scan_status CHECK (secret_scan_status IN ('CLEAN', 'CONFIRMED_WITH_FINDINGS'))
);

CREATE INDEX ix_snippets__workspace_language_framework ON snippets (workspace_id, language, framework);
CREATE INDEX ix_snippets__workspace_last_used ON snippets (workspace_id, last_used_at DESC);

-- 불변 테이블: 애플리케이션은 INSERT와 SELECT만 한다. 영구 삭제 시 parent와 함께 cascade로 제거된다.
CREATE TABLE snippet_versions (
    id               UUID         NOT NULL DEFAULT gen_random_uuid(),
    workspace_id     UUID         NOT NULL,
    snippet_node_id  UUID         NOT NULL,
    version_no       INTEGER      NOT NULL,
    code             TEXT         NOT NULL,
    change_summary   VARCHAR(200),
    content_hash     CHAR(64)     NOT NULL,
    created_by       UUID         NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_snippet_versions PRIMARY KEY (id),
    CONSTRAINT uq_snippet_versions__snippet_version UNIQUE (snippet_node_id, version_no),
    CONSTRAINT fk_snippet_versions__snippets FOREIGN KEY (workspace_id, snippet_node_id)
        REFERENCES snippets (workspace_id, node_id) ON DELETE CASCADE,
    CONSTRAINT fk_snippet_versions__users FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT ck_snippet_versions__version_no CHECK (version_no >= 1),
    -- §21.6 한 버전 512 KB. 애플리케이션 검증이 1차, 이 제약이 최종 방어선이다.
    CONSTRAINT ck_snippet_versions__code CHECK (length(code) > 0 AND octet_length(code) <= 524288)
);

CREATE INDEX ix_snippet_versions__workspace_snippet_version ON snippet_versions (workspace_id, snippet_node_id, version_no DESC);
-- Phase 5 코드 검색용(SRCH-02는 현재 버전만 대상으로 조회 단계에서 제한한다).
CREATE INDEX ix_snippet_versions__code_trgm ON snippet_versions USING gin (code gin_trgm_ops);
