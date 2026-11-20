-- 镜像就绪状态：把"这台机器上这个工具链镜像能不能用"变成跨运行复用的事实。
--
-- 动机：镜像拉取失败以前只留下一条 E-302，下一次运行又从零预检、又从零等一轮超时。
-- 参考 kubelet 的 ImagePullBackOff：按镜像维度记状态，失败后按 next_retry_at 退避，
-- 成功则记 READY + digest，重复构建不必反复探测。
--
-- host 是 Docker 守护所在主机：状态描述「这台机器的镜像层缓存」，多实例共用同一守护时
-- 共享，不同主机之间不可共享（因此进主键）。
CREATE TABLE IF NOT EXISTS image_readiness (
    host text NOT NULL,
    image text NOT NULL,
    status text NOT NULL,
    source text,
    digest text,
    attempts integer NOT NULL DEFAULT 0,
    last_error text,
    next_retry_at bigint NOT NULL DEFAULT 0,
    updated_at bigint NOT NULL,
    PRIMARY KEY (host, image)
);

CREATE INDEX IF NOT EXISTS idx_image_readiness_status ON image_readiness (status, next_retry_at);
