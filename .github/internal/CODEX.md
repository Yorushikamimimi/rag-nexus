# RAG-Nexus — 开发上下文备忘（内部文档，不对外展示）

> 此文件仅供本地开发参考，已从仓库根目录移入 .github/internal/，不影响 GitHub 首页展示。
> 目录结构和脚本路径已更新：scraper_service.py → scripts/scraper_service.py，yorushika_agent.py → scripts/batch_ingest_agent.py。

---

## 技术栈

| 层 | 技术 |
|----|------|
| **后端** | Spring Boot 3.4 + Spring AI 1.0 |
| **AI / LLM** | 阿里云 DashScope（`qwen-plus` 对话 + `text-embedding-v2` 向量） |
| **向量数据库** | PostgreSQL 16 + pgvector（HNSW 索引，1536 维，余弦相似度） |
| **抓取微服务** | Python FastAPI + yt-dlp（B 站 / YouTube 视频元数据） |
| **前端** | Vue 3 + TypeScript + Vite + TailwindCSS |
| **容器编排** | Docker Compose（本地开发可单独启动各服务） |

---

## 项目结构（当前实际路径）

```
rag-nexus/
├── src/                        # Spring Boot 后端
│   └── main/
│       ├── java/com/ragnexus/  # Controller / Service / Domain
│       └── resources/
│           ├── application.yml
│           └── application-local.yml   # gitignored，需手动创建
├── rag-nexus-web/              # Vue 3 前端
├── scripts/
│   ├── scraper_service.py      # FastAPI 抓取微服务（:8000）
│   └── batch_ingest_agent.py   # 批量视频语料灌入脚本
├── docker-compose.yml
├── Dockerfile
├── Dockerfile.python
├── init-pgvector.sql
└── requirements-scraper.txt
```

---

## 核心架构

```
用户浏览器
    │
    ▼
Vue 3 SPA (:5173 开发 / :80 生产)
    │  /api/* 代理
    ▼
Spring Boot (:8080)
    ├─── pgvector（向量相似度检索）──► PostgreSQL :5433
    ├─── FastAPI 抓取服务（视频元数据）──► :8000
    └─── 阿里云 DashScope（LLM 对话 + 嵌入）──► 外网
```

---

## 端口一览

| 服务 | 端口 | 说明 |
|------|------|------|
| Vue 前端 | 5173 | 本地开发模式 |
| Spring Boot | 8080 | REST API + Swagger |
| Python 抓取服务 | 8000 | FastAPI |
| PostgreSQL+pgvector | 5433 | 宿主机映射（容器内 5432） |

---

## 配置要点

- `application-local.yml` 被 `.gitignore` 忽略，需手动创建（见 `LOCAL_DEV_README.md`）
- `spring.profiles.active: local` 在 `application.yml` 中硬编码
- `base-url` 设为 `https://dashscope.aliyuncs.com/compatible-mode`，**不加 `/v1` 后缀**（Spring AI 自动拼接，加了会 404）
- pgvector 的 `initialize-schema: true` 首次启动自动建表

---

## 数据库注意事项

- 本地容器名：`rag-nexus-pg`（或 `rag-nexus-pgvector`，取决于启动方式）
- 启动命令：`docker start rag-nexus-pg`（不要用 `docker run`，否则会创建空容器丢失数据）
- 数据表：`vector_store`（Spring AI 自动创建管理）

---

## 常用命令速查

```bash
# 启动数据库
docker start rag-nexus-pg

# 查看知识库条数
docker exec rag-nexus-pg psql -U postgres -d postgres -c "SELECT COUNT(*) FROM vector_store;"

# 启动后端
mvn spring-boot:run

# 启动前端
cd rag-nexus-web && npm run dev

# 启动 Python 抓取服务
cd scripts && uvicorn scraper_service:app --host 0.0.0.0 --port 8000 --reload

# 批量灌入视频语料
python scripts/batch_ingest_agent.py

# Swagger UI
open http://localhost:8080/swagger-ui.html
```
