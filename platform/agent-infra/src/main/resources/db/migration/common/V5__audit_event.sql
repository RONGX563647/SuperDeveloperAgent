-- BUG-37：append-only 审计表。应用层只 INSERT（DbAuditTrail），不提供 UPDATE/DELETE 路径，
-- 清理任务也只清 change_plans / code_patches / run_ledger，不动本表。
CREATE TABLE IF NOT EXISTS audit_event (
    id bigint NOT NULL PRIMARY KEY,
    ts bigint NOT NULL,
    actor text NOT NULL,
    role text,
    action text NOT NULL,
    target_type text NOT NULL,
    target_id text,
    project_id text,
    env text,
    result text,
    detail_json text
);

CREATE INDEX IF NOT EXISTS idx_audit_target ON audit_event (target_type, target_id, ts DESC);
CREATE INDEX IF NOT EXISTS idx_audit_actor_ts ON audit_event (actor, ts DESC);
CREATE INDEX IF NOT EXISTS idx_audit_project_ts ON audit_event (project_id, ts DESC);
