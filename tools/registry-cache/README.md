# 本地镜像缓存源（pull-through cache）

目的：让构建用到的工具链镜像（node / maven / busybox…）**只从 Docker Hub 拉一次**，
之后都走本机缓存。这样冷拉 63s 的 `node:22-bookworm` 第二次是秒级，也顺带绕开 Hub
的匿名拉取限流。

这不是"调大超时"的替代品，而是把"网络慢"这个根因从关键路径上拿掉；平台侧仍然保留
进度感知超时、退避重试与源回退（见 `agent-executor/ImageProvisioner`）兜底。

## 起一个缓存源

```bash
cd tools/registry-cache
docker compose up -d
curl -s http://localhost:5000/v2/          # 期望：{}
```

缓存数据放在命名卷 `registry-cache` 里，容器重启不丢层；清理用
`docker compose down -v`（会丢缓存，谨慎）。

## 让平台用它

```bash
# 直连失败后按序回退；本地缓存排第一，公网加速源兜底
AGENT_SANDBOX_PULL_MIRRORS=localhost:5000,docker.m.daocloud.io
```

平台会用 `localhost:5000/library/<官方镜像>` 拉取，成功后自动 `docker tag` 回原名，
后续 `docker run` 完全无感（与 Testcontainers 的镜像名前缀改写同一思路）。

首次拉某个镜像仍会走公网（同时写缓存），第二次开始走本地；这正是我们要的
"第一次慢、之后都省"的模式。

## 什么时候不该用

- 镜像自带 registry（`quay.io/...`、`localhost:5000/...`）：源链回退会跳过，避免拼出
  不存在的仓库；
- 完全离线环境：改用预加载（`docker save` / `docker load` 或 `kind load docker-image`），
  并把 `agent.sandbox.precheck-image` 按需关闭。

## 参考

- distribution pull-through cache 配方：<https://distribution.github.io/distribution/recipes/mirror/>
- containerd hosts.toml 多源回退（同一思路的守护进程侧实现）：
  <https://github.com/containerd/containerd/blob/main/docs/hosts.md>
