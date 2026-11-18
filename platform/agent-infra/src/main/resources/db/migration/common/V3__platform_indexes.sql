-- 二级索引：Hibernate ddl-auto 不会生成，必须由迁移脚本固化。
-- 全部使用 IF NOT EXISTS，SQLite / PostgreSQL 通用。
CREATE INDEX IF NOT EXISTS idx_timeline_conv_seq ON conversation_timeline (conversation_id, seq);
CREATE INDEX IF NOT EXISTS idx_conversations_proj_owner ON conversations (project_id, owner_user_id, pinned DESC, last_activity_at DESC);
CREATE INDEX IF NOT EXISTS idx_runs_conv_created ON conversation_runs (conversation_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_runs_proj_goal ON conversation_runs (project_id, goal_key, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_ledger_proj_updated ON run_ledger (project_id, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_ledger_watchdog ON run_ledger (updated_at) WHERE state IN ('RUNNING', 'QUEUED');
CREATE INDEX IF NOT EXISTS idx_tasks_proj ON workspace_tasks (project_id, priority DESC, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_tasks_conv ON workspace_tasks (conversation_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_token_user ON token_usage (project_id, created_at DESC);
