-- 설계서 §12.3 relation_types / knowledge_relations (Phase 4), §13.1 시스템 Relation 13종 seed.
-- layout_positions(GRPH-06, SHOULD)는 이번 Phase 범위에서 제외했다(설계서 §19: "선택 시").

CREATE TABLE relation_types (
    id                    UUID         NOT NULL DEFAULT gen_random_uuid(),
    workspace_id          UUID,
    key                   VARCHAR(50)  NOT NULL,
    forward_label         VARCHAR(100) NOT NULL,
    inverse_label         VARCHAR(100) NOT NULL,
    description           VARCHAR(300),
    directionality        VARCHAR(20)  NOT NULL,
    allowed_source_types  TEXT[]       NOT NULL,
    allowed_target_types  TEXT[]       NOT NULL,
    is_system             BOOLEAN      NOT NULL DEFAULT FALSE,
    is_active             BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_relation_types PRIMARY KEY (id),
    CONSTRAINT fk_relation_types__workspaces FOREIGN KEY (workspace_id) REFERENCES workspaces (id),
    CONSTRAINT ck_relation_types__directionality CHECK (directionality IN ('directed', 'symmetric')),
    -- node_type은 §11.2의 닫힌 집합이므로 별도 규칙 테이블 대신 CHECK로 강제한다. 새 타입을 추가하면 §11.2와 함께 migration한다.
    CONSTRAINT ck_relation_types__allowed_source_types CHECK (
        allowed_source_types <@ ARRAY['CONCEPT','NOTE','SNIPPET','ERROR','SOLUTION','RESOURCE','PROJECT']::text[]
        AND cardinality(allowed_source_types) > 0),
    CONSTRAINT ck_relation_types__allowed_target_types CHECK (
        allowed_target_types <@ ARRAY['CONCEPT','NOTE','SNIPPET','ERROR','SOLUTION','RESOURCE','PROJECT']::text[]
        AND cardinality(allowed_target_types) > 0),
    -- 시스템 타입은 workspace_id가 NULL, 사용자 타입은 Workspace 소속(사용자 타입은 Growth 범위, §13.2)
    CONSTRAINT ck_relation_types__system_scope CHECK ((is_system AND workspace_id IS NULL) OR (NOT is_system AND workspace_id IS NOT NULL))
);

CREATE UNIQUE INDEX uq_relation_types__system_key ON relation_types (key) WHERE workspace_id IS NULL;
CREATE UNIQUE INDEX uq_relation_types__workspace_key ON relation_types (workspace_id, lower(key)) WHERE workspace_id IS NOT NULL;

-- §13.1 / §13.1.1: 이 seed가 허용 Source/Target 조합의 유일한 진실 소스다.
INSERT INTO relation_types
    (key, forward_label, inverse_label, description, directionality, allowed_source_types, allowed_target_types, is_system)
VALUES
    ('RELATED_TO', 'related to', 'related to', '의미가 구체적이지 않지만 관련 있음', 'symmetric',
        ARRAY['CONCEPT','NOTE','SNIPPET','ERROR','SOLUTION','RESOURCE','PROJECT'],
        ARRAY['CONCEPT','NOTE','SNIPPET','ERROR','SOLUTION','RESOURCE','PROJECT'], TRUE),
    ('IS_PART_OF', 'is part of', 'has part', '구성/포함', 'directed',
        ARRAY['CONCEPT','NOTE','SNIPPET','ERROR','SOLUTION','RESOURCE','PROJECT'],
        ARRAY['CONCEPT','NOTE','SNIPPET','ERROR','SOLUTION','RESOURCE','PROJECT'], TRUE),
    ('DEPENDS_ON', 'depends on', 'dependency of', '기술적 선행/의존', 'directed',
        ARRAY['CONCEPT','SNIPPET'], ARRAY['CONCEPT'], TRUE),
    ('IS_EXAMPLE_OF', 'is example of', 'has example', '코드/메모가 개념을 예시', 'directed',
        ARRAY['SNIPPET','NOTE'], ARRAY['CONCEPT'], TRUE),
    ('IMPLEMENTS', 'implements', 'implemented by', '구현체–개념', 'directed',
        ARRAY['SNIPPET'], ARRAY['CONCEPT'], TRUE),
    ('USED_IN', 'used in', 'uses', '지식·Snippet이 프로젝트에서 활용됨', 'directed',
        ARRAY['CONCEPT','NOTE','SNIPPET','RESOURCE'], ARRAY['PROJECT'], TRUE),
    ('OCCURRED_IN', 'occurred in', 'had occurrence of', 'Error가 특정 프로젝트에서 발생함', 'directed',
        ARRAY['ERROR'], ARRAY['PROJECT'], TRUE),
    ('APPLIED_IN', 'applied in', 'applied from', 'Solution이 프로젝트에 실제 적용됨', 'directed',
        ARRAY['SOLUTION'], ARRAY['PROJECT'], TRUE),
    ('CAUSED_BY', 'caused by', 'causes', '오류 원인', 'directed',
        ARRAY['ERROR'], ARRAY['CONCEPT'], TRUE),
    ('SOLVED_BY', 'solved by', 'solves', '오류–해결', 'directed',
        ARRAY['ERROR'], ARRAY['SOLUTION'], TRUE),
    ('IMPLEMENTED_WITH', 'implemented with', 'implements solution', '해결–코드', 'directed',
        ARRAY['SOLUTION'], ARRAY['SNIPPET'], TRUE),
    ('LEARNED_FROM', 'learned from', 'source of learning', '학습 출처', 'directed',
        ARRAY['CONCEPT','NOTE'], ARRAY['RESOURCE'], TRUE),
    ('REFERENCES', 'references', 'referenced by', '일반 참조', 'directed',
        ARRAY['CONCEPT','NOTE','SNIPPET','ERROR','SOLUTION','RESOURCE','PROJECT'], ARRAY['RESOURCE'], TRUE);

CREATE TABLE knowledge_relations (
    id               UUID         NOT NULL DEFAULT gen_random_uuid(),
    workspace_id     UUID         NOT NULL,
    source_node_id   UUID         NOT NULL,
    target_node_id   UUID         NOT NULL,
    relation_type_id UUID         NOT NULL,
    note             VARCHAR(500),
    created_by       UUID         NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_knowledge_relations PRIMARY KEY (id),
    -- Node를 영구 삭제하면 연결된 edge도 함께 제거된다(§11.3 cascade).
    CONSTRAINT fk_knowledge_relations__source FOREIGN KEY (workspace_id, source_node_id)
        REFERENCES knowledge_nodes (workspace_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_knowledge_relations__target FOREIGN KEY (workspace_id, target_node_id)
        REFERENCES knowledge_nodes (workspace_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_knowledge_relations__relation_types FOREIGN KEY (relation_type_id) REFERENCES relation_types (id),
    CONSTRAINT fk_knowledge_relations__users FOREIGN KEY (created_by) REFERENCES users (id),
    -- self-loop 전면 금지(§12.3). 애플리케이션이 먼저 검사하고 이 CHECK는 최종 방어선이다.
    CONSTRAINT ck_knowledge_relations__no_self_loop CHECK (source_node_id <> target_node_id),
    CONSTRAINT uq_knowledge_relations__edge UNIQUE (workspace_id, source_node_id, relation_type_id, target_node_id)
);

CREATE INDEX ix_knowledge_relations__source ON knowledge_relations (workspace_id, source_node_id, relation_type_id);
CREATE INDEX ix_knowledge_relations__target ON knowledge_relations (workspace_id, target_node_id, relation_type_id);
