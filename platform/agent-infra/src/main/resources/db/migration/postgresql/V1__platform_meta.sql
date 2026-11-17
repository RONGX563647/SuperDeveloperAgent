-- 持久化层元信息：记录 schema 版本与初始化标记。
CREATE TABLE IF NOT EXISTS platform_meta (
    meta_key   VARCHAR(128) PRIMARY KEY,
    meta_value TEXT,
    updated_at BIGINT NOT NULL
);
