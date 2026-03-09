# ============================================================
# AgentOps Server — Multi-stage Docker Build
# ============================================================

# ---------- Stage 1: Build ----------
FROM maven:3.9-eclipse-temurin-17-alpine AS builder

WORKDIR /build

# 先复制 pom.xml 利用 Docker 缓存下载依赖
COPY pom.xml .
RUN mvn dependency:go-offline -B

# 复制源码并打包（跳过测试）
COPY src ./src
RUN mvn package -DskipTests -B && \
    mv target/agentops-server-*.jar target/app.jar

# ---------- Stage 2: Runtime ----------
FROM eclipse-temurin:17-jre-alpine

LABEL maintainer="agentops-team"
LABEL description="AgentOps — 多智能体协同 AIOps 排障专家系统"

# 安全: 非 root 用户
RUN addgroup -S app && adduser -S app -G app

WORKDIR /app

COPY --from=builder /build/target/app.jar app.jar

# 健康检查
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
    CMD wget -qO- http://localhost:8080/actuator/health || exit 1

# JVM 调优参数
ENV JAVA_OPTS="-Xms512m -Xmx1024m -XX:+UseG1GC -XX:MaxGCPauseMillis=200"

USER app

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -jar app.jar --spring.profiles.active=docker"]
