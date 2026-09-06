# ---- 构建阶段：源码进镜像，fresh clone 可直接 docker build ----
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
# 先拷 pom 拉依赖，充分利用层缓存（源码变更不触发依赖重新下载）
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B package -DskipTests && mv target/*.jar target/app.jar

# ---- 运行阶段 ----
FROM eclipse-temurin:17-jre AS runtime
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
# 非 root 运行
RUN useradd -r -u 1001 appuser
WORKDIR /app
COPY --from=build /src/target/app.jar app.jar
USER appuser
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=5 \
  CMD curl -fsS http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
