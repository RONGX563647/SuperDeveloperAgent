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
# 沙箱模式必须是 process：容器内没有（也刻意永不挂载）docker.sock，
# 而 agent.sandbox.mode 默认 docker 且 fail-closed —— 不改这一项，
# 容器会在 SandboxConfig.actionRunner 处启动失败：
#   agent.sandbox.mode=docker 但 Docker 守护进程不可用
# 容器部署下隔离边界由容器本身承担；需要更强隔离请改用 gVisor/Kata 运行时。
ENV AGENT_SANDBOX_MODE=process
COPY platform/agent-gateway/target/*-exec.jar /app/app.jar
# 前端产物：platform/web/dist（由 frontend_build 阶段产出）
COPY platform/web/dist/ /app/web/
ENV AGENT_WEB_DIST=/app/web
ENV SERVER_PORT=8090
EXPOSE 8090
ENTRYPOINT ["java","-jar","/app/app.jar"]
