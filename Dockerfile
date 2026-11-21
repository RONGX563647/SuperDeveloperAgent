# 由平台按仓库结构生成（ensure_dockerfile 同款规则）
# Java 模块：platform/agent-gateway（jar 由 backend_build 阶段产出）
#
# COPY 必须锁定 *-exec.jar：gateway 的 repackage 声明了 <classifier>exec</classifier>，
# 所以 target 下同时有 agent-gateway-*.jar（普通包，**没有 Main-Class**）和
# agent-gateway-*-exec.jar（Spring Boot 可执行包）。
# 拷错普通包的后果：容器启动即 no main manifest attribute, in /app/app.jar；
# 两个都匹配 * 时 Docker 还会因"多源复制到文件目标"直接构建失败。
FROM eclipse-temurin:21-jre
WORKDIR /app
ENV TZ=Asia/Shanghai

# ---------------------------------------------------------------------------
# 沙箱模式：不在镜像里写死 AGENT_SANDBOX_MODE。
#
# 原实现 `ENV AGENT_SANDBOX_MODE=process`，注释说"容器部署下隔离边界由容器本身承担"。
# 这句话只在"平台容器内不跑不可信代码"时成立，而本平台的业务恰恰是在容器里跑仓库里的
# 构建配方——于是容器化部署下**完全没有内核级隔离**，且没人会注意到（默认值是 docker，
# 被这行 ENV 覆盖成 process，fail-closed 也就失效了）。
#
# 现在的约定：
#   · 默认（不设该变量）→ docker 模式 → 找不到守护进程就启动失败（fail-closed 恢复本意）
#   · 容器化部署 → 用 docker-compose.platform.yml 的 dind sidecar 提供守护进程
#   · 确实不跑不可信代码的单机场景 → 显式 `-e AGENT_SANDBOX_MODE=process`
# ---------------------------------------------------------------------------
# 数据目录：工作区、SQLite、HOST 部署状态都在这里，必须挂卷，否则容器重建即丢
ENV AGENT_WORKSPACE_ROOT=/data/workspaces \
    AGENT_SQLITE_FILE=/data/_platform/platform.db \
    AGENT_WEB_DIST=/app/web \
    SERVER_PORT=8090

# 非 root 运行：原镜像以 root 启动，容器内任何 RCE 直接拿到 root（配合 docker.sock 即宿主）。
# curl 供 HEALTHCHECK 使用；Java 21 的 jre 镜像基于 Ubuntu，apt 可用。
RUN groupadd -g 10001 agent \
 && useradd -u 10001 -g agent -M -s /usr/sbin/nologin agent \
 && apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && mkdir -p /data && chown -R 10001:10001 /data /app

COPY --chown=10001:10001 platform/agent-gateway/target/*-exec.jar /app/app.jar
# 前端产物：platform/web/dist（由 frontend_build 阶段产出）
COPY --chown=10001:10001 platform/web/dist/ /app/web/

VOLUME ["/data"]
USER 10001:10001
EXPOSE 8090

# 健康检查用 actuator：起不来就没有"部署成功"这回事。
# start-period 90s：Spring Boot + JPA + Flyway 冷启动在这台机上实测约 40~60s。
HEALTHCHECK --interval=30s --timeout=3s --start-period=90s --retries=3 \
  CMD curl -fsS "http://127.0.0.1:${SERVER_PORT}/actuator/health" || exit 1

ENTRYPOINT ["java","-jar","/app/app.jar"]
