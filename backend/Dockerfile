# 构建层与运行层基础镜像版本固定（docs/13 §3.9 供应链基线）
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build/backend
# 先复制构建描述并预取依赖，源码变更时可复用依赖层缓存
COPY backend/mvnw backend/pom.xml ./
COPY backend/.mvn ./.mvn
RUN chmod +x mvnw
COPY backend/flexforge-common/pom.xml flexforge-common/
COPY backend/flexforge-app/pom.xml flexforge-app/
RUN ./mvnw -B -ntp -q dependency:go-offline || true
COPY database /build/database
COPY backend/flexforge-common/src flexforge-common/src
COPY backend/flexforge-app/src flexforge-app/src
RUN ./mvnw -B -ntp package -DskipTests

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
# 非 root 运行
RUN addgroup -S flexforge && adduser -S flexforge -G flexforge
COPY --from=build /build/backend/flexforge-app/target/*.jar app.jar
USER flexforge
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
