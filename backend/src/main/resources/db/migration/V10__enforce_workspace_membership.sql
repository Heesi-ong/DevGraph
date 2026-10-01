-- V9 is reserved for Phase 6 subtype tables. Existing migrations remain immutable.
-- Reject inconsistent existing data before adding constraints; do not silently delete or reassign ownership.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM (
            SELECT workspace_id, created_by AS user_id FROM knowledge_nodes
            UNION ALL SELECT workspace_id, created_by FROM snippet_versions
            UNION ALL SELECT workspace_id, created_by FROM knowledge_relations
            UNION ALL SELECT workspace_id, user_id FROM favorites
            UNION ALL SELECT workspace_id, user_id FROM node_views
            UNION ALL SELECT workspace_id, actor_user_id FROM activity_logs
        ) refs
        WHERE NOT EXISTS (SELECT 1 FROM workspace_members m
                          WHERE m.workspace_id = refs.workspace_id AND m.user_id = refs.user_id)
    ) THEN
        RAISE EXCEPTION 'Workspace membership preflight failed; repair orphan references before migration';
    END IF;
END $$;

ALTER TABLE knowledge_nodes ADD CONSTRAINT fk_knowledge_nodes__workspace_member
    FOREIGN KEY (workspace_id, created_by) REFERENCES workspace_members (workspace_id, user_id);
ALTER TABLE snippet_versions ADD CONSTRAINT fk_snippet_versions__workspace_member
    FOREIGN KEY (workspace_id, created_by) REFERENCES workspace_members (workspace_id, user_id);
ALTER TABLE knowledge_relations ADD CONSTRAINT fk_knowledge_relations__workspace_member
    FOREIGN KEY (workspace_id, created_by) REFERENCES workspace_members (workspace_id, user_id);
ALTER TABLE favorites ADD CONSTRAINT fk_favorites__workspace_member
    FOREIGN KEY (workspace_id, user_id) REFERENCES workspace_members (workspace_id, user_id);
ALTER TABLE node_views ADD CONSTRAINT fk_node_views__workspace_member
    FOREIGN KEY (workspace_id, user_id) REFERENCES workspace_members (workspace_id, user_id);
ALTER TABLE activity_logs ADD CONSTRAINT fk_activity_logs__workspace_member
    FOREIGN KEY (workspace_id, actor_user_id) REFERENCES workspace_members (workspace_id, user_id);

CREATE INDEX ix_auth_sessions__family_created ON auth_sessions (family_id, created_at DESC, id DESC);
