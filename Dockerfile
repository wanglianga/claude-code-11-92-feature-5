# syntax=docker/dockerfile:1

# ---------- 构建阶段 ----------
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /build

# 国内构建走阿里云 Maven 镜像（如在海外网络可删除下一行复制与 settings 引用）
COPY docker/settings-docker.xml /root/.m2/settings.xml

# 先预取依赖，利用层缓存
COPY pom.xml ./
RUN mvn -B -q -s /root/.m2/settings.xml dependency:go-offline

COPY src ./src
# 集成验证依赖外部 PostgreSQL，镜像构建阶段跳过测试；测试可在本机/Compose 网络内执行
RUN mvn -B -s /root/.m2/settings.xml clean package -DskipTests \
    && cp target/berth-coordination.jar /build/app.jar

# ---------- 运行阶段 ----------
FROM eclipse-temurin:25-jre
WORKDIR /app

ENV TZ=Asia/Shanghai \
    JAVA_OPTS="-XX:MaxRAMPercentage=75.0" \
    DB_HOST=db \
    DB_PORT=5432 \
    DB_NAME=berth \
    DB_USER=berth \
    DB_PASSWORD=berth123 \
    HOLD_TTL_SECONDS=120

COPY --from=build /build/app.jar /app/app.jar

EXPOSE 8080
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
