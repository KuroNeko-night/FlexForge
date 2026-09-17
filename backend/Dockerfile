# 构建层与运行层基础镜像版本固定（docs/13 §3.9 供应链基线）
FROM maven:3-eclipse-temurin-26 AS build
WORKDIR /build/backend
# 先复制构建描述并预取依赖，源码变更时可复用依赖层缓存
COPY backend/mvnw backend/pom.xml ./
COPY backend/.mvn ./.mvn
COPY backend/config ./config
RUN chmod +x mvnw
COPY backend/flexforge-common/pom.xml flexforge-common/
COPY backend/flexforge-runtime/pom.xml flexforge-runtime/
COPY backend/flexforge-auth/pom.xml flexforge-auth/
COPY backend/flexforge-system/pom.xml flexforge-system/
COPY backend/flexforge-meta/pom.xml flexforge-meta/
COPY backend/flexforge-data/pom.xml flexforge-data/
COPY backend/flexforge-plugin/pom.xml flexforge-plugin/
COPY backend/flexforge-issue/pom.xml flexforge-issue/
COPY backend/flexforge-ai/pom.xml flexforge-ai/
COPY backend/flexforge-kb/pom.xml flexforge-kb/
COPY backend/flexforge-app/pom.xml flexforge-app/
RUN ./mvnw -B -ntp -q dependency:go-offline || true
COPY database /build/database
COPY backend/flexforge-common/src flexforge-common/src
COPY backend/flexforge-runtime/src flexforge-runtime/src
COPY backend/flexforge-auth/src flexforge-auth/src
COPY backend/flexforge-system/src flexforge-system/src
COPY backend/flexforge-meta/src flexforge-meta/src
COPY backend/flexforge-data/src flexforge-data/src
COPY backend/flexforge-plugin/src flexforge-plugin/src
COPY backend/flexforge-issue/src flexforge-issue/src
COPY backend/flexforge-ai/src flexforge-ai/src
COPY backend/flexforge-kb/src flexforge-kb/src
COPY backend/flexforge-app/src flexforge-app/src
RUN ./mvnw -B -ntp package -DskipTests

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
# P20 数据处理器运行时（ADR-0002 Level 2）：python3 仅标准库、无 pip 安装面（S6 修订）
RUN apk add --no-cache python3
# 非 root 运行
RUN addgroup -S flexforge && adduser -S flexforge -G flexforge
COPY --from=build /build/backend/flexforge-app/target/*.jar app.jar
USER flexforge
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
