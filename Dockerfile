# RAG 知识库后端 - 多阶段构建
# ========== Build 阶段 ==========
FROM maven:3.9-eclipse-temurin-17 AS builder

WORKDIR /build

# 复制 POM 并下载依赖（利用 Docker 层缓存）
COPY pom.xml .
RUN mvn dependency:go-offline -B

# 复制源码并打包
COPY src ./src
RUN mvn clean package -DskipTests -B

# ========== Run 阶段 ==========
FROM eclipse-temurin:17-jre

WORKDIR /app

# 从 builder 阶段拷贝 jar 包（Spring Boot 打包为 rag-nexus-1.0.0-SNAPSHOT.jar）
COPY --from=builder /build/target/rag-nexus-*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
