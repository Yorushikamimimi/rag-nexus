# 本地开发指南

> 完整的启动步骤和接口说明见 [README.md](README.md)。本文档补充本地开发中的数据库操作、调试命令和常见问题。

---

## 快速启动（日常）

```bash
# 1. 启动数据库（保留历史数据）
docker start rag-nexus-pg

# 2. Python 抓取微服务（scripts/ 目录下）
cd scripts && uvicorn scraper_service:app --host 0.0.0.0 --port 8000 --reload

# 3. Spring Boot 后端
mvn spring-boot:run

# 4. Vue 前端
cd rag-nexus-web && npm run dev
```

| 服务 | 地址 |
|------|------|
| 前端 | http://localhost:5173 |
| Swagger UI | http://localhost:8080/swagger-ui.html |
| Python 服务文档 | http://localhost:8000/docs |

---

## 首次初始化（全新环境）

### 1. 启动 pgvector 数据库容器

```bash
docker run -d \
  --name rag-nexus-pg \
  -e POSTGRES_USER=postgres \
  -e POSTGRES_PASSWORD=postgres \
  -e POSTGRES_DB=postgres \
  -p 5433:5432 \
  pgvector/pgvector:pg16

docker exec rag-nexus-pg psql -U postgres -c "CREATE EXTENSION IF NOT EXISTS vector;"
```

> 之后始终用 `docker start rag-nexus-pg` 启动，不要重复 `docker run`（会创建空容器丢失数据）。

### 2. 创建 `src/main/resources/application-local.yml`

此文件已被 `.gitignore` 忽略，需手动创建：

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5433/postgres
    username: postgres
    password: postgres
  ai:
    openai:
      base-url: https://dashscope.aliyuncs.com/compatible-mode  # 末尾不加 /v1
      api-key: <你的 DashScope API Key>
      chat:
        options:
          model: qwen-plus
          temperature: 0.3
      embedding:
        options:
          model: text-embedding-v2
    vectorstore:
      pgvector:
        index-type: HNSW
        distance-type: COSINE_DISTANCE
        dimensions: 1536
        initialize-schema: true
```

### 3. 安装依赖

```bash
pip install -r requirements-scraper.txt
cd rag-nexus-web && npm install
```

---

## 知识库管理

```bash
# 查看当前 chunk 总数
docker exec rag-nexus-pg psql -U postgres -d postgres -c "SELECT COUNT(*) FROM vector_store;"

# 批量灌入视频元数据（修改 target_urls 后运行）
python scripts/batch_ingest_agent.py
```

---

## 常见问题

| 现象 | 原因 & 解决 |
|------|-------------|
| 向量 embedding 报 404 | `base-url` 末尾不要加 `/v1`，Spring AI 会自动拼接 |
| 数据库连接失败 | 运行 `docker start rag-nexus-pg` 确认容器已启动 |
| 历史知识数据丢失 | 误用了 `docker run` 创建了新容器，改用 `docker start` |
| 中文回答出现乱码 | 检查 `application.yml` 中 `server.servlet.encoding.force=true` 是否生效 |
