-- token 用量分项归因（token 爆炸专项补充验收点）：本轮加载了哪些插件、各占多少 schema 字符。
--
-- 两列都可空：老记录没有归因，读出来为 NULL；新记录由 TokenUsageRecorder 从
-- ProgressiveToolMiddleware 写入 RuntimeContext 的归因填充。schema 字符数是 token 的确定性
-- 代理（英文约 4 字符/token），与 TokenBaselineTest 同口径，不引入 tokenizer 依赖。
--
-- 通用方言：ALTER TABLE ... ADD COLUMN <col> text 在 PostgreSQL 与 SQLite 均可用。
-- 放在 common 目录，两库共用一份；JPA ddl-auto 在 Flyway 之后执行，列已存在会跳过。
ALTER TABLE token_usage ADD COLUMN activated_plugins text;
ALTER TABLE token_usage ADD COLUMN plugin_schema_chars text;
