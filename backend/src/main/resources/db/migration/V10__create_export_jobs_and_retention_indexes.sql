-- 설계서 §12.3 export_jobs(1.0 필수), §21.5 보존 배치용 index (Phase 7).
CREATE TABLE export_jobs (
    id                       UUID         NOT NULL DEFAULT gen_random_uuid(),
    workspace_id             UUID         NOT NULL,
    requested_by             UUID         NOT NULL,
    status                   VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    format                   VARCHAR(20)  NOT NULL DEFAULT 'ZIP',
    include_archived         BOOLEAN      NOT NULL DEFAULT FALSE,
    file_storage_key         VARCHAR(100),
    file_size_bytes          BIGINT,
    download_token_hash      BYTEA,
    download_token_expires_at TIMESTAMPTZ,
    manifest_checksum        CHAR(64),
    failure_reason           VARCHAR(100),
    retry_count              INTEGER      NOT NULL DEFAULT 0,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),
    started_at               TIMESTAMPTZ,
    completed_at             TIMESTAMPTZ,
    -- 생성된 파일의 보관 기한(완료 후 1시간, §9.8). 이 시각이 지나면 정리 배치가 파일을 지우고 EXPIRED로 바꾼다.
    expires_at               TIMESTAMPTZ,
    CONSTRAINT pk_export_jobs PRIMARY KEY (id),
    CONSTRAINT fk_export_jobs__workspaces FOREIGN KEY (workspace_id) REFERENCES workspaces (id),
    CONSTRAINT fk_export_jobs__users FOREIGN KEY (requested_by) REFERENCES users (id),
    CONSTRAINT uq_export_jobs__download_token_hash UNIQUE (download_token_hash),
    CONSTRAINT ck_export_jobs__status CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED', 'EXPIRED')),
    CONSTRAINT ck_export_jobs__format CHECK (format IN ('ZIP')),
    CONSTRAINT ck_export_jobs__retry_count CHECK (retry_count >= 0)
);

CREATE INDEX ix_export_jobs__workspace_user_created ON export_jobs (workspace_id, requested_by, created_at DESC);
CREATE INDEX ix_export_jobs__status_expires ON export_jobs (status, expires_at);
-- 사용자당 동시에 진행 가능한 job은 1개(§12.3, §17.6). 애플리케이션 사전 검사가 먼저 응답하고 이 인덱스가 경합의 최종 방어선이다.
CREATE UNIQUE INDEX uq_export_jobs__active_per_user ON export_jobs (requested_by) WHERE status IN ('PENDING', 'PROCESSING');

-- §21.5 보존 배치(휴지통 30일, activity 180일, 감사 365일, 재인증 토큰 정리)가 전체 스캔을 하지 않도록.
CREATE INDEX ix_knowledge_nodes__trashed_at ON knowledge_nodes (trashed_at) WHERE status = 'TRASHED';
CREATE INDEX ix_activity_logs__created_at ON activity_logs (created_at);
CREATE INDEX ix_security_audit_logs__created_at ON security_audit_logs (created_at);
CREATE INDEX ix_reauth_tokens__expires_at ON reauth_tokens (expires_at);
