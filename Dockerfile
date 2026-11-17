# 由平台按仓库结构生成（ensure_dockerfile 同款规则）
# Java 模块：platform/agent-gateway（jar 由 backend_build 阶段产出）
FROM eclipse-temurin:21-jre
WORKDIR /app
ENV TZ=Asia/Shanghai
COPY platform/agent-gateway/target/*.jar /app/app.jar
# 前端产物：platform/web/dist（由 frontend_build 阶段产出）
COPY platform/web/dist/ /app/web/
ENV AGENT_WEB_DIST=/app/web
ENV SERVER_PORT=8090
EXPOSE 8090
ENTRYPOINT ["java","-jar","/app/app.jar"]
