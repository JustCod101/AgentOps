FROM maven:3.9-eclipse-temurin-17 AS builder

WORKDIR /build

COPY pom.xml .
RUN mvn dependency:go-offline -B

COPY src ./src
RUN mvn package -DskipTests -B && \
    mv target/agentops-server-*.jar target/app.jar

FROM eclipse-temurin:17-jre-jammy

LABEL maintainer="agentops-team"
LABEL description="AgentOps — 多智能体协同 AIOps 排故专家系统"

RUN groupadd --gid 10001 app && \
    useradd --uid 10001 --gid app --shell /bin/false --create-home app

WORKDIR /app

COPY --from=builder /build/target/app.jar app.jar

HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
    CMD wget -qO- http://localhost:8080/actuator/health || exit 1

ENV JAVA_OPTS="-Xms512m -Xmx1024m -XX:+UseG1GC -XX:MaxGCPauseMillis=200"

USER app

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -jar app.jar --spring.profiles.active=docker"]
