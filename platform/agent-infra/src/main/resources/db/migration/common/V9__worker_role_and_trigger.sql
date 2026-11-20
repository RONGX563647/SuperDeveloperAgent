-- 角色 Worker 与事件接入：只补维度，不建新表。
-- source_ref/trigger_type：事件（Gitee issue/PR）如何变成工作区任务；
-- worker_role/trigger_ref：运行是由哪个角色、因哪个触发源发起的。
-- SQLite 与 PostgreSQL 都支持 ALTER TABLE ... ADD COLUMN。
ALTER TABLE workspace_tasks ADD COLUMN source_ref text;
ALTER TABLE workspace_tasks ADD COLUMN trigger_type text;
ALTER TABLE run_ledger ADD COLUMN worker_role text;
ALTER TABLE run_ledger ADD COLUMN trigger_ref text;
