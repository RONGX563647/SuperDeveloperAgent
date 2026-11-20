-- 删除「登记地址」app_url：生效端口一律由 EffectiveEndpointResolver 从租约/流水线实时解析。
--
-- 动机：project_envs.app_url 是接入时写死的占位值（test=8180 / prod=8181），既不参与部署，
-- 又永远和真实生效端口对不齐。list_envs 曾为此长期打 warning，让 Agent 和人都去纠结一个
-- 根本没有消费者的字段。既然对外事实只有「生效地址 + 来源」，这个登记列可以直接删除。
--
-- 兼容：V1 是历史迁移（存量库 baseline-version=1 不会重跑），此处用增量迁移删列。
-- SQLite 3.35+ 与 PostgreSQL 均支持 DROP COLUMN [IF EXISTS] 的语义；SQLite 不支持 IF EXISTS，
-- 但本迁移只在 V1 建表之后执行，列必然存在，故直接 DROP。
ALTER TABLE project_envs DROP COLUMN app_url;
